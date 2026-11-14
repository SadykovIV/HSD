package com.hsd.universal.dex;

import java.io.ByteArrayInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Read only a named boolean from the activated user's SharedPreferences map. */
final class ActivationPreferences extends DefaultHandler2 {
    static final int MAX_BYTES = 256 * 1024;
    private int depth;
    private Boolean value;
    private boolean insideFlag;
    private final String key;

    private ActivationPreferences(String key) { this.key = key; }

    static boolean hudEnabled(byte[] document) {
        try { return read(document, "metaNavi", true); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static boolean read(byte[] document, String key, boolean fallback) {
        if (document.length == 0 || document.length > MAX_BYTES) throw new IllegalArgumentException("Invalid settings size");
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            ActivationPreferences flag = new ActivationPreferences(key);
            reader.setContentHandler(flag);
            reader.setErrorHandler(flag);
            reader.setEntityResolver((id, system) -> { throw new SAXException("Unexpected external entity"); });
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", flag);
            reader.parse(new InputSource(new ByteArrayInputStream(document)));
            return flag.value == null ? fallback : flag.value;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid HSD settings"); }
    }

    @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
        throw new SAXException("Unexpected preference DTD");
    }

    @Override public void startElement(String uri, String local, String qName, Attributes attrs) throws SAXException {
        depth++;
        if (depth == 1 && (!uri.isEmpty() || !local.equals("map"))) throw new SAXException("Expected map");
        if (insideFlag) throw new SAXException("Unexpected child of boolean");
        if (depth == 2 && key.equals(attrs.getValue("", "name"))) {
            String raw = attrs.getValue("", "value");
            if (value != null || !uri.isEmpty() || !local.equals("boolean") ||
                    !("true".equals(raw) || "false".equals(raw))) throw new SAXException("Invalid activation flag");
            value = Boolean.valueOf(raw);
            insideFlag = true;
        }
    }

    @Override public void characters(char[] text, int start, int count) throws SAXException {
        if (insideFlag) for (int i = start; i < start + count; i++) {
            if (!Character.isWhitespace(text[i])) throw new SAXException("Unexpected boolean text");
        }
    }

    @Override public void endElement(String uri, String local, String qName) {
        if (depth == 2) insideFlag = false;
        depth--;
    }
}
