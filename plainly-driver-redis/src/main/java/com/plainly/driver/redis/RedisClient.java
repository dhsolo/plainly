package com.plainly.driver.redis;

import com.plainly.driver.DbException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条 Redis 连接，说 RESP2。
 *
 * <h2>为什么不引 Jedis</h2>
 * RESP2 只有五种回复类型，请求永远是一个「批量字符串数组」。整套编解码就是下面
 * 这一百多行。引一个客户端库要跟着进来连接池、日志门面、JSON 库好几个传递依赖，
 * 而这个工具要的只是「发一条命令，把回复原样拿回来」。
 *
 * <p>更要紧的是<b>值的处理由自己把关</b>：Redis 的值就是字节串，
 * 本类<b>不做任何数值解析</b>——{@code :1234567890123456789} 这样的整数回复
 * 也按文本留着。客户端库为了好用往往会替你转成 {@code long} 或者 {@code double}，
 * 那正是本项目不能接受的那条路。
 *
 * <h2>二进制安全</h2>
 * 回复按字节保存，{@link Reply#text()} 只在这段字节确实是合法 UTF-8 时才给出文本。
 * 存的是 protobuf 或者压缩过的内容时，硬解成 UTF-8 会得到一串替换字符 U+FFFD——
 * 看着像数据坏了，实际是显示的锅。那种情况下 {@link Reply#display()} 给的是
 * 长度加十六进制摘要，和网格里 BLOB 列的处理方式一致。
 *
 * <p>不是线程安全的：一条连接同一时刻只发一条命令。
 */
public final class RedisClient implements AutoCloseable {

    /** 回复里一个批量字符串的长度上限，防止一条 {@code GET} 把几百兆拉进内存。 */
    private static final int MAX_BULK_BYTES = 8 * 1024 * 1024;

    /** 摘要里显示多少字节的十六进制。和 {@code CellReader} 保持一致。 */
    private static final int PREVIEW_BYTES = 8;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private RedisClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new java.io.BufferedInputStream(socket.getInputStream(), 16 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 8 * 1024);
    }

    public static RedisClient connect(String host, int port, int connectTimeoutMs,
                                      int readTimeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);
            return new RedisClient(s);
        } catch (IOException e) {
            closeQuietly(s);
            throw new DbException("连不上 Redis " + host + ":" + port + "：" + e.getMessage(), e);
        }
    }

    /**
     * 等一条服务端<b>主动推来</b>的消息。
     *
     * <p>普通命令是「我问一句、它答一句」，订阅模式反过来：连接切进订阅模式之后
     * 只出不进，消息什么时候来完全看发布方。所以这里没有对应的写操作，
     * 只是阻塞着读下一条回复。
     *
     * <p>读超时到了会抛 {@link java.net.SocketTimeoutException} 包出来的异常——
     * 调用方应当先把超时设成 0（永不超时）或者接住它继续等，
     * 见 {@link #setReadTimeout}。
     */
    public Reply awaitPush() {
        try {
            return readReply();
        } catch (IOException e) {
            throw new DbException("等待订阅消息时连接出错：" + e.getMessage(), e);
        }
    }

    /**
     * 改读超时。
     *
     * <p>普通命令要短超时——服务端不回话时不能让界面一直卡着。
     * 订阅正相反：一条消息可能几小时才来一次，短超时会把正常的空等
     * 当成故障报出来。所以订阅那条连接建好之后要把它设成 0。
     */
    public void setReadTimeout(int millis) {
        try {
            socket.setSoTimeout(millis);
        } catch (java.net.SocketException e) {
            throw new DbException("设置读超时失败：" + e.getMessage(), e);
        }
    }

    /**
     * 发一条命令，返回回复。
     *
     * <p>服务端回的是错误时<b>抛异常</b>而不是返回一个错误对象：调用方几乎总是
     * 忘记检查后者，然后把 {@code -WRONGTYPE ...} 当成数据显示出来。
     */
    public Reply command(String... args) {
        Reply reply = raw(args);
        if (reply.kind() == Kind.ERROR) {
            throw new DbException("Redis 拒绝了 " + args[0] + "：" + reply.text());
        }
        return reply;
    }

    /** 同 {@link #command}，但错误也作为回复返回——命令行控制台要照实显示错误。 */
    public Reply raw(String... args) {
        try {
            writeCommand(args);
            out.flush();
            return readReply();
        } catch (IOException e) {
            throw new DbException("Redis 通信失败：" + e.getMessage(), e);
        }
    }

    /**
     * 一次把多条命令发出去，再按顺序收回来。
     *
     * <p>浏览一屏键要的信息是「类型、TTL、大小、值预览」，一行四条命令。
     * 一页两百行就是八百次往返——本机也要好几秒，跨机房直接没法用。
     * 管道把它压成一次发送、一次接收。RESP 天然支持：回复严格按请求顺序回来，
     * 不需要请求 ID，也不需要服务端做什么。
     *
     * <p>错误<b>不抛</b>，原样放在对应位置：一批里某个键刚好被别人删了，
     * 不该让整页都读不出来。
     */
    public List<Reply> pipeline(List<String[]> commands) {
        if (commands.isEmpty()) {
            return List.of();
        }
        try {
            for (String[] args : commands) {
                writeCommand(args);
            }
            out.flush();
            List<Reply> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) {
                replies.add(readReply());
            }
            return replies;
        } catch (IOException e) {
            throw new DbException("Redis 通信失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 编码

    private void writeCommand(String[] args) throws IOException {
        out.write('*');
        writeAsciiInt(args.length);
        crlf();
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write('$');
            writeAsciiInt(bytes.length);
            crlf();
            out.write(bytes);
            crlf();
        }
    }

    private void writeAsciiInt(int value) throws IOException {
        out.write(Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
    }

    private void crlf() throws IOException {
        out.write('\r');
        out.write('\n');
    }

    // ------------------------------------------------------------------ 解码

    private Reply readReply() throws IOException {
        int marker = in.read();
        if (marker < 0) {
            throw new IOException("连接已被对端关闭");
        }
        switch (marker) {
            case '+':
                return new Reply(Kind.STATUS, readLineBytes(), null);
            case '-':
                return new Reply(Kind.ERROR, readLineBytes(), null);
            case ':':
                return new Reply(Kind.INTEGER, readLineBytes(), null);
            case '$':
                return readBulk();
            case '*':
                return readArray();
            default:
                throw new IOException("看不懂的 RESP 标记：'" + (char) marker + "'（0x"
                        + Integer.toHexString(marker) + "）");
        }
    }

    private Reply readBulk() throws IOException {
        long length = parseLong(readLineBytes());
        if (length < 0) {
            return Reply.nil();
        }
        if (length > MAX_BULK_BYTES) {
            // 不读进来，但也不能把连接留在半读状态——后面的字节还在管道里，
            // 下一条命令会拿到上一条的尾巴。所以照样读完，只是不保留
            skipExactly(length + 2);
            return new Reply(Kind.BULK,
                    ("[值过大，" + length + " 字节，未读取]").getBytes(StandardCharsets.UTF_8), null);
        }
        byte[] data = new byte[(int) length];
        readFully(data);
        skipExactly(2); // CRLF
        return new Reply(Kind.BULK, data, null);
    }

    private Reply readArray() throws IOException {
        long count = parseLong(readLineBytes());
        if (count < 0) {
            return Reply.nil();
        }
        List<Reply> items = new ArrayList<>((int) Math.min(count, 1024));
        for (long i = 0; i < count; i++) {
            items.add(readReply());
        }
        return new Reply(Kind.ARRAY, null, items);
    }

    /** 读到 CRLF 为止，返回不含 CRLF 的字节。 */
    private byte[] readLineBytes() throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(32);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IOException("RESP 行没有以 CRLF 结束");
                }
                return buf.toByteArray();
            }
            buf.write(c);
        }
        throw new IOException("连接在读完一行之前就断了");
    }

    private void readFully(byte[] target) throws IOException {
        int read = 0;
        while (read < target.length) {
            int n = in.read(target, read, target.length - read);
            if (n < 0) {
                throw new IOException("连接在读完一个批量回复之前就断了");
            }
            read += n;
        }
    }

    private void skipExactly(long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("连接在跳过数据时断了");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static long parseLong(byte[] line) throws IOException {
        try {
            return Long.parseLong(new String(line, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new IOException("RESP 长度字段不是数字："
                    + new String(line, StandardCharsets.US_ASCII));
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public boolean isClosed() {
        return socket.isClosed() || !socket.isConnected();
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 关不上也没什么可做的
        }
    }

    // ------------------------------------------------------------------ 回复

    public enum Kind {
        /** {@code +OK} */
        STATUS,
        /** {@code -ERR ...} */
        ERROR,
        /** {@code :123}。<b>按文本保留，不解析成 long。</b> */
        INTEGER,
        /** {@code $3\r\nabc} */
        BULK,
        /** {@code $-1} 或 {@code *-1}：键不存在。和空字符串是两回事。 */
        NIL,
        /** {@code *2\r\n...} */
        ARRAY
    }

    /**
     * 一条回复。
     *
     * @param data  STATUS / ERROR / INTEGER / BULK 的字节内容；其余为 null
     * @param items ARRAY 的元素；其余为 null
     */
    public record Reply(Kind kind, byte[] data, List<Reply> items) {

        private static final Reply NIL = new Reply(Kind.NIL, null, null);

        public static Reply nil() {
            return NIL;
        }

        public boolean isNil() {
            return kind == Kind.NIL;
        }

        /**
         * 按 UTF-8 解出文本；不是合法 UTF-8 就返回 null。
         *
         * <p>用严格解码而不是默认的「非法字节替换成 U+FFFD」：替换是<b>不可逆</b>的，
         * 一旦发生，界面上看到的就不再是库里的东西了，而且看不出来。
         * 宁可这里返回 null，让上层改用 {@link #display()} 显示成二进制摘要。
         */
        public String text() {
            if (data == null) {
                return null;
            }
            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(data))
                        .toString();
            } catch (CharacterCodingException e) {
                return null;
            }
            /*
             * 解得出来还不够，还得<b>看得见</b>。
             *
             * 位图就是个反例：{@code SETBIT key 7 1} 存进去的是一个字节 0x01，
             * 它是合法的 UTF-8（一个控制字符），于是解码成功、返回一个长度为 1 的字符串，
             * 在网格里<b>什么也看不到</b>——用户以为这个键是空的。
             *
             * 所以带控制字符的一律按二进制走，交给上层显示成十六进制摘要。
             * 制表、换行、回车除外，它们在文本值里很常见，也确实是文本。
             */
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) {
                    return null;
                }
            }
            return decoded;
        }

        /**
         * 预览专用：按 UTF-8 解，允许末尾少半个字。
         *
         * <h2>为什么单独一个方法</h2>
         * 值预览是拿 {@code GETRANGE key 0 199} 取的——切在<b>第 200 个字节</b>上。
         * 一个汉字占三字节，所以这一刀十有八九落在某个字的中间，
         * 剩下的一两个字节不是合法 UTF-8。
         *
         * <p>{@link #text()} 严格解码，遇到这种情况返回 null，于是整格显示成
         * {@code [二进制 200 B 0x...]}——一段好好的中文，在网格里看着像坏掉了。
         *
         * <p>这里的做法是：把末尾最多三个字节逐个去掉再试，能解出来就用，
         * 解不出来才认定它<b>真的</b>是二进制。既不放过真正的二进制值，
         * 也不因为一刀切在字中间就冤枉一段中文。
         *
         * @return 解出来的文本；确实不是 UTF-8 时返回 null
         */
        public String previewText() {
            if (data == null) {
                return null;
            }
            for (int drop = 0; drop <= 3 && drop < data.length; drop++) {
                byte[] slice = java.util.Arrays.copyOf(data, data.length - drop);
                String text = decodeStrict(slice);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }

        private static String decodeStrict(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                return null;
            }
        }

        /** 给界面看的一段文本。NIL 返回 null（和 SQL 的 NULL 对齐），二进制返回摘要。 */
        public String display() {
            if (kind == Kind.NIL) {
                return null;
            }
            if (kind == Kind.ARRAY) {
                return "[" + (items == null ? 0 : items.size()) + " 个元素]";
            }
            String text = text();
            return text != null ? text : describeBinary(data);
        }

        /** 数组元素；不是数组就是空表。 */
        public List<Reply> list() {
            return items == null ? List.of() : items;
        }

        private static String describeBinary(byte[] bytes) {
            /*
             * HyperLogLog 的头四个字节固定是 HYLL，这是它的存储格式规定的。
             * 认出来直接说，比让用户对着一串十六进制猜强——而且从 TYPE 上看
             * 它就是个普通 string，没有别的地方能告诉他这是什么。
             */
            if (bytes.length >= 4 && bytes[0] == 'H' && bytes[1] == 'Y'
                    && bytes[2] == 'L' && bytes[3] == 'L') {
                return "[HyperLogLog，" + bytes.length + " B；基数用 PFCOUNT 看]";
            }
            StringBuilder sb = new StringBuilder("[二进制 ").append(bytes.length).append(" B");
            if (bytes.length > 0) {
                sb.append(" 0x");
                int n = Math.min(PREVIEW_BYTES, bytes.length);
                for (int i = 0; i < n; i++) {
                    sb.append(String.format("%02X", bytes[i]));
                }
                if (bytes.length > n) {
                    sb.append('…');
                }
            }
            return sb.append(']').toString();
        }
    }
}
