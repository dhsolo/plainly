package com.plainly.core.task;

import com.plainly.driver.DbException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * 任务结果的邮件通知。
 *
 * <p>只在任务失败时发，或者用户明确要求每次都发——一个每天成功的备份任务
 * 每天发一封「成功」的邮件，两周之后就没人看了，真出事那封也一样被忽略。
 *
 * <p>SMTP 口令和数据库口令走同一套 DPAPI 加密，不明文存。
 */
public final class MailNotifier {

    /** 发信配置。 */
    public record Config(String host, int port, boolean startTls, String user, String password,
                         String from, String to) {

        public boolean usable() {
            return host != null && !host.isBlank() && to != null && !to.isBlank();
        }
    }

    private MailNotifier() {
    }

    /**
     * 发一封。
     *
     * <p>发信失败不抛给调用方——任务本身的成败与通知能不能送出去是两回事，
     * 因为发不出邮件就把一次成功的备份记成失败，只会更误导人。
     *
     * @return 发出去了返回 null，失败返回原因
     */
    public static String send(Config config, String subject, String body) {
        if (!config.usable()) {
            return "没有配置收件人";
        }
        Properties props = new Properties();
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port() <= 0 ? 587 : config.port()));
        props.put("mail.smtp.auth", String.valueOf(
                config.user() != null && !config.user().isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.startTls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.user(), config.password());
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            String from = config.from() == null || config.from().isBlank()
                    ? config.user() : config.from();
            message.setFrom(new InternetAddress(from));
            for (String one : config.to().split("[,;]")) {
                if (!one.isBlank()) {
                    message.addRecipient(Message.RecipientType.TO,
                            new InternetAddress(one.trim()));
                }
            }
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
            return null;
        } catch (MessagingException e) {
            return e.getMessage();
        }
    }

    /** 测试发信，界面上的「发一封试试」用。这里失败要抛出来——用户就是来看它成不成的。 */
    public static void test(Config config) {
        String problem = send(config, "Plainly 测试邮件",
                "这是一封测试邮件。收到它说明任务通知能正常送达。");
        if (problem != null) {
            throw new DbException("发信失败：" + problem);
        }
    }
}
