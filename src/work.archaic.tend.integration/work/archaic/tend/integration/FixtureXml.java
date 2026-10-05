package work.archaic.tend.integration;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/** Sort composed public fixture declarations into the schema's explicit resource sequence. */
final class FixtureXml {
    static String ordered(String xml) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var root = document.getDocumentElement();
            var children = new ArrayList<Element>();
            var order = List.of("secret", "configuration", "volume", "instance", "ingress-gateway", "ingress", "egress");
            for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element element)) continue;
                if (!order.contains(element.getTagName())) throw new IOException("Unknown fixture resource");
                children.add(element);
            }
            children.sort(Comparator.comparingInt(element -> order.indexOf(element.getTagName())));
            for (var child : children) root.appendChild(child);
            var writer = new StringWriter();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | TransformerException failure) {
            throw new IOException("Cannot compose public fixture XML", failure);
        }
    }
}
