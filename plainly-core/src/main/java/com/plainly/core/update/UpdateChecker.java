package com.plainly.core.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 查一次有没有新版本。
 *
 * <h2>这一版做什么、不做什么</h2>
 * 做：取一份版本清单、和本机版本比一比、把结论交给界面。
 * <b>不做</b>：下载、校验、执行安装程序。
 *
 * <p>不做的理由在 README「没有自动更新」里：没有代码签名证书时，
 * 让程序在无人值守的情况下跑起一个 SmartScreen 会拦的 MSI，比让用户手动下载更糟。
 * 那个前提到今天仍然成立，所以这里到「打开发布页」为止。
 *
 * <h2>为什么把取数据抽成 Fetcher</h2>
 * 不是为了以后换实现，是为了<b>能测</b>。CONTRIBUTING 立过规矩：单元测试
 * 一律不依赖外部服务。版本比较、清单解析、跳过逻辑——真正会出错的全在这几处，
 * 而它们都不需要网络。把那一次 HTTP 挡在接口后面，这些就都能用假数据跑。
 */
public class UpdateChecker {

    /** 取一段文本。实现可以抛任何异常，调用方会把它转成给用户看的一句话。 */
    public interface Fetcher {
        String get(String url) throws Exception;
    }

    public enum Status {
        /** 没填发布地址。此时<b>一个网络请求也没发</b>。 */
        NOT_CONFIGURED,
        /** 本机版本认不出来（从源码跑，jar 里没有 manifest），没法比。 */
        UNKNOWN_CURRENT,
        /** 已经是最新的。 */
        UP_TO_DATE,
        /** 有更新的版本。 */
        AVAILABLE,
        /** 没查成。网络不通、地址不对、对方改了格式，都归这里。 */
        FAILED
    }

    /**
     * 一次检查的结论。
     *
     * @param release {@link Status#AVAILABLE} 时是那个新版本；其余情况下可能为
     *                {@code null}，也可能是「当前最新的那一个」（已是最新时）
     * @param message 给用户看的一句话。每种结论都有，包括成功的那几种——
     *                「检查完了什么都没发生」如果界面上什么都不显示，
     *                用户分不清是查过了还是根本没查
     */
    public record Result(Status status, Release release, String message) {

        public boolean isAvailable() {
            return status == Status.AVAILABLE;
        }
    }

    private final UpdateSettings settings;
    private final Fetcher fetcher;

    public UpdateChecker(UpdateSettings settings, Fetcher fetcher) {
        this.settings = settings;
        this.fetcher = fetcher;
    }

    /**
     * 查一次。<b>不抛异常</b>——所有失败都变成 {@link Status#FAILED} 的一句话。
     *
     * <p>要在后台线程上调用：里面有一次网络往返。
     *
     * @param currentVersionText 本机版本号原文，取自 jar 的 {@code Implementation-Version}
     */
    public Result check(String currentVersionText) {
        String repo = settings.repo();
        if (repo == null) {
            return new Result(Status.NOT_CONFIGURED, null,
                    "还没填发布地址，所以没有检查。");
        }

        Version current = Version.tryParse(currentVersionText);
        if (current == null) {
            // 当成 0.0.0 的话，任何正式版本都比它大，于是从源码跑的人
            // 每次启动都被告知「有新版本」——而他那台机器上压根没有 MSI 要装
            return new Result(Status.UNKNOWN_CURRENT, null,
                    "本机版本号取不到（多半是从源码运行的），没法和发布版比较。");
        }

        String body;
        try {
            body = fetcher.get(ReleaseFeed.latestUrl(repo));
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Result(Status.FAILED, null, "没查成：" + detail);
        }

        Release release = ReleaseFeed.parse(body, repo);
        if (release == null) {
            // 分不清是「还没发过正式版」还是「对方换了格式」，就别替用户下结论。
            // 把地址摆出来，他自己点开一眼就知道是哪一种
            return new Result(Status.FAILED, null,
                    "读不出发布信息。" + repo + " 上可能还没有正式发布，"
                            + "也可能是草稿或预发布——可以打开 "
                            + ReleaseFeed.releasesPage(repo) + " 看一眼。");
        }

        if (release.version().isNewerThan(current)) {
            return new Result(Status.AVAILABLE, release,
                    "有新版本 " + release.version().text()
                            + "（本机 " + current.text() + "）。");
        }
        return new Result(Status.UP_TO_DATE, release,
                "已经是最新的：" + current.text() + "。");
    }

    // ------------------------------------------------------------------ 默认实现

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 走 HTTPS 的默认实现。
     *
     * <h2>为什么一定要带 User-Agent</h2>
     * GitHub 的 API 对不带 User-Agent 的请求直接回 403。不带的话，现象是
     * 「网络明明是通的，但永远检查失败」，而错误信息里不会提 User-Agent 一个字。
     *
     * <h2>超时必须设</h2>
     * 不设的话 {@link HttpClient} 会一直等。这个调用挂在启动流程上，
     * 用户处在一个被墙掉 GitHub 的网络里时（这在国内是常态），
     * 那个后台线程会挂到天荒地老。
     *
     * <p>不带任何凭据：这是个公开接口，匿名就能读。带 token 意味着要存 token，
     * 而这个工具已经在保管数据库口令了，不该再多保管一样东西。
     */
    public static Fetcher httpFetcher(String appVersion) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "Plainly/" + appVersion)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                return response.body();
            }
            // 这三个各有各的处理办法，混成一句「请求失败 4xx」等于什么都没说
            throw new IllegalStateException(switch (code) {
                case 404 -> "找不到这个仓库，或者它还没有发布过正式版本（404）";
                case 403, 429 -> "被 GitHub 限流了，过一会儿再试（" + code + "）";
                default -> "GitHub 返回 " + code;
            });
        };
    }
}
