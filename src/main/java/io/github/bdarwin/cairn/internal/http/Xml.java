package io.github.bdarwin.cairn.internal.http;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;

/** Writing S3's XML responses, and parsing request bodies with DTDs and external entities refused. */
final class Xml {

    static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private Xml() {
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // XML 1.0 cannot carry most control characters even escaped; S3 writes them as character references.
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append("&#").append((int) c).append(';');
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Appends {@code <name>value</name>}, escaped. */
    static StringBuilder element(StringBuilder sb, String name, Object value) {
        return sb.append('<').append(name).append('>').append(escape(String.valueOf(value))).append("</").append(name).append('>');
    }

    static Document parse(byte[] body) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new ByteArrayInputStream(body)));
        } catch (Exception e) {
            throw new io.github.bdarwin.cairn.internal.S3Exception(400, "MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema.");
        }
    }
}
