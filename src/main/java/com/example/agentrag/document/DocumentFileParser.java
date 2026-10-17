package com.example.agentrag.document;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DocumentFileParser {
    private final DocumentParsingProperties properties;

    public DocumentFileParser(DocumentParsingProperties properties) {
        this.properties = properties;
    }

    public ParsedDocument parse(InputStream input, String filename, String mediaType) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        StructuredMarkdownHandler handler = new StructuredMarkdownHandler(properties.maxCharacters);
        AtomicReference<String> unsupportedEmbedded = new AtomicReference<>();
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata embeddedMetadata) {
                String embeddedType = embeddedMetadata.get(Metadata.CONTENT_TYPE);
                if (embeddedType == null || !embeddedType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    String name = embeddedMetadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                    unsupportedEmbedded.compareAndSet(null, name == null ? "unknown" : name);
                }
                return false;
            }

            @Override public void parseEmbedded(InputStream stream, ContentHandler contentHandler,
                                                Metadata embeddedMetadata, boolean outputHtml)
                    throws SAXException, IOException {
                // shouldParseEmbedded始终返回false；此方法仅满足Tika接口约定。
            }
        });
        try {
            new AutoDetectParser().parse(input, handler, metadata, context);
        } catch (EncryptedDocumentException exception) {
            throw new DocumentParsingException("ENCRYPTED_DOCUMENT", "文件已加密，无法解析", false, exception);
        } catch (CharacterLimitException exception) {
            throw new DocumentParsingException("TEXT_TOO_LARGE", "提取文本超过" + properties.maxCharacters + "字符", false, exception);
        } catch (Exception exception) {
            throw new DocumentParsingException("PARSE_ERROR", "文档解析失败：" + safeMessage(exception), true, exception);
        }
        if (unsupportedEmbedded.get() != null) {
            throw new DocumentParsingException("UNSUPPORTED_EMBEDDED_OBJECT",
                    "文档包含不支持的嵌入对象：" + unsupportedEmbedded.get(), false);
        }
        String text = normalize(handler.markdown());
        int pages = parsePages(metadata);
        if (pages > properties.maxPdfPages) {
            throw new DocumentParsingException("TOO_MANY_PAGES", "PDF页数超过" + properties.maxPdfPages + "页", false);
        }
        if (text.isBlank()) {
            if ("application/pdf".equals(mediaType)) {
                throw new DocumentParsingException("OCR_REQUIRED", "PDF没有可提取文本，可能是扫描件，需要OCR", false);
            }
            throw new DocumentParsingException("EMPTY_DOCUMENT", "文件中没有可提取文本", false);
        }
        return new ParsedDocument(text, pages);
    }

    private int parsePages(Metadata metadata) {
        for (String key : new String[]{"xmpTPg:NPages", "Page-Count", "meta:page-count"}) {
            String value = metadata.get(key);
            if (value != null) try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String safeMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName()
                : message.substring(0, Math.min(300, message.length()));
    }

    public record ParsedDocument(String markdown, int pageCount) { }

    private static final class CharacterLimitException extends SAXException {
        private CharacterLimitException() { super("character limit reached"); }
    }

    private static final class StructuredMarkdownHandler extends DefaultHandler {
        private final int limit;
        private final StringBuilder output = new StringBuilder();
        private int headingLevel;
        private boolean inListItem;
        private boolean inTableRow;
        private boolean firstTableCell;

        private StructuredMarkdownHandler(int limit) { this.limit = limit; }

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            String tag = tag(localName, qName);
            if (tag.matches("h[1-6]")) {
                newline(2); headingLevel = tag.charAt(1) - '0'; append("#".repeat(headingLevel) + " ");
            } else if ("p".equals(tag) || "div".equals(tag)) newline(2);
            else if ("li".equals(tag)) { newline(1); inListItem = true; append("- "); }
            else if ("br".equals(tag)) newline(1);
            else if ("tr".equals(tag)) { newline(1); inTableRow = true; firstTableCell = true; }
            else if ("td".equals(tag) || "th".equals(tag)) {
                if (inTableRow && !firstTableCell) append(" | ");
                firstTableCell = false;
            }
        }

        @Override public void endElement(String uri, String localName, String qName) throws SAXException {
            String tag = tag(localName, qName);
            if (tag.matches("h[1-6]")) { headingLevel = 0; newline(2); }
            else if ("p".equals(tag) || "div".equals(tag)) newline(2);
            else if ("li".equals(tag)) { inListItem = false; newline(1); }
            else if ("tr".equals(tag)) { inTableRow = false; newline(1); }
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            append(new String(ch, start, length));
        }

        private void append(String value) throws CharacterLimitException {
            if (output.length() + value.length() > limit) throw new CharacterLimitException();
            output.append(value);
        }

        private void newline(int amount) throws CharacterLimitException {
            int existing = 0;
            for (int i = output.length() - 1; i >= 0 && output.charAt(i) == '\n'; i--) existing++;
            if (existing < amount) append("\n".repeat(amount - existing));
        }

        private String tag(String localName, String qName) {
            String value = localName == null || localName.isBlank() ? qName : localName;
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        }

        private String markdown() { return output.toString(); }
    }
}
