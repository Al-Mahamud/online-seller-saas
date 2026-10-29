package com.mojud.shared.email;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds an email in one language from message keys ({@code email.*} in messages.properties and
 * messages_bn.properties): the same blocks become the plain text and the HTML, in Mojud's layout.
 * Every value is escaped in the HTML. Snapshot tests keep the result reviewed (EmailTemplateTests).
 *
 * <pre>
 * composer.in(locale).subject("email.otp.subject", code)
 *         .paragraph("email.otp.intro", minutes).code(code).note("email.otp.ignore")
 *         .build();
 * </pre>
 */
@Component
public class EmailComposer {

    private static final String FONT = "'Hind Siliguri','Noto Sans Bengali',Arial,Helvetica,sans-serif";

    private final MessageSource messages;

    EmailComposer(MessageSource messages) {
        this.messages = messages;
    }

    public Draft in(Locale locale) {
        return new Draft(locale);
    }

    private sealed interface Block permits Paragraph, Code, Action, Table, Note {
    }

    private record Paragraph(String text) implements Block {
    }

    private record Code(String code) implements Block {
    }

    private record Action(String label, String url) implements Block {
    }

    private record Table(List<String> header, List<List<String>> rows) implements Block {
    }

    private record Note(String text) implements Block {
    }

    /** One email being written; blocks appear in the order they are added. */
    public final class Draft {

        private final Locale locale;
        private final List<Block> blocks = new ArrayList<>();
        private String subject;

        private Draft(Locale locale) {
            this.locale = locale;
        }

        public Locale locale() {
            return locale;
        }

        /** The text of a message key in this email's language. */
        public String text(String key, Object... args) {
            return messages.getMessage(key, args, locale);
        }

        /** A whole number as written in this language (Bangla digits in Bangla). */
        public String number(long value) {
            return NumberFormat.getIntegerInstance(locale).format(value);
        }

        public Draft subject(String key, Object... args) {
            subject = text(key, args);
            return this;
        }

        public Draft paragraph(String key, Object... args) {
            blocks.add(new Paragraph(text(key, args)));
            return this;
        }

        /** A paragraph of text already in this language (such as a notification's title). */
        public Draft paragraphText(String text) {
            blocks.add(new Paragraph(text));
            return this;
        }

        /** A one-time code, set apart and large. */
        public Draft code(String code) {
            blocks.add(new Code(code));
            return this;
        }

        /** A button in HTML, the label and link on their own line in text. */
        public Draft action(String labelKey, String url, Object... args) {
            blocks.add(new Action(text(labelKey, args), url));
            return this;
        }

        /** A table; header cells are message keys, row cells plain text. */
        public Draft table(List<String> headerKeys, List<List<String>> rows) {
            blocks.add(new Table(headerKeys.stream().map(this::text).toList(), rows));
            return this;
        }

        /** Small print, after the content. */
        public Draft note(String key, Object... args) {
            blocks.add(new Note(text(key, args)));
            return this;
        }

        public EmailMessage build() {
            if (subject == null) {
                throw new IllegalStateException("An email needs a subject");
            }
            return new EmailMessage(subject, text(), html());
        }

        private String text() {
            StringBuilder out = new StringBuilder();
            for (Block block : blocks) {
                if (block instanceof Paragraph p) {
                    out.append(p.text());
                } else if (block instanceof Code c) {
                    out.append("    ").append(c.code());
                } else if (block instanceof Action a) {
                    out.append(a.label()).append(":\n").append(a.url());
                } else if (block instanceof Table t) {
                    out.append(String.join(" | ", t.header()));
                    for (List<String> row : t.rows()) {
                        out.append('\n').append("- ").append(String.join(" | ", row));
                    }
                } else if (block instanceof Note n) {
                    out.append(n.text());
                }
                out.append("\n\n");
            }
            return out.append("-- \n").append(text("email.brand")).append('\n').append(text("email.footer")).append('\n')
                    .toString();
        }

        private String html() {
            StringBuilder body = new StringBuilder();
            for (Block block : blocks) {
                if (block instanceof Paragraph p) {
                    body.append("<p style=\"margin:0 0 16px;\">").append(escape(p.text())).append("</p>\n");
                } else if (block instanceof Code c) {
                    body.append("<p style=\"margin:0 0 16px;font-family:Consolas,Menlo,monospace;font-size:28px;"
                            + "font-weight:bold;letter-spacing:6px;color:#0f766e;\">").append(escape(c.code())).append("</p>\n");
                } else if (block instanceof Action a) {
                    body.append("<p style=\"margin:8px 0 24px;\"><a href=\"").append(escape(a.url()))
                            .append("\" style=\"display:inline-block;background:#0f766e;color:#ffffff;text-decoration:none;"
                                    + "padding:10px 20px;border-radius:6px;font-weight:bold;\">")
                            .append(escape(a.label())).append("</a></p>\n");
                } else if (block instanceof Table t) {
                    body.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                            + "style=\"border-collapse:collapse;margin:0 0 16px;font-size:14px;\">\n<tr>");
                    for (String cell : t.header()) {
                        body.append("<th align=\"left\" style=\"padding:6px 8px;border-bottom:2px solid #e4e4e7;\">")
                                .append(escape(cell)).append("</th>");
                    }
                    body.append("</tr>\n");
                    for (List<String> row : t.rows()) {
                        body.append("<tr>");
                        for (String cell : row) {
                            body.append("<td style=\"padding:6px 8px;border-bottom:1px solid #f4f4f5;\">")
                                    .append(escape(cell)).append("</td>");
                        }
                        body.append("</tr>\n");
                    }
                    body.append("</table>\n");
                } else if (block instanceof Note n) {
                    body.append("<p style=\"margin:0 0 16px;font-size:13px;color:#71717a;\">")
                            .append(escape(n.text())).append("</p>\n");
                }
            }
            return """
                    <!doctype html>
                    <html lang="%s">
                    <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s</title>
                    </head>
                    <body style="margin:0;padding:0;background:#f4f4f5;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5;">
                    <tr><td align="center" style="padding:24px 12px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border-radius:8px;font-family:%s;color:#18181b;font-size:15px;line-height:1.6;">
                    <tr><td style="padding:20px 28px;border-bottom:1px solid #e4e4e7;font-size:20px;font-weight:bold;color:#0f766e;">%s</td></tr>
                    <tr><td style="padding:24px 28px 8px;">
                    %s</td></tr>
                    <tr><td style="padding:16px 28px;border-top:1px solid #e4e4e7;font-size:12px;color:#71717a;">%s</td></tr>
                    </table>
                    </td></tr>
                    </table>
                    </body>
                    </html>
                    """.formatted(locale.getLanguage(), escape(subject), FONT, escape(text("email.brand")), body,
                    escape(text("email.footer")));
        }
    }

    /** HTML-escaped, line breaks kept. */
    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8").replace("\n", "<br>");
    }
}
