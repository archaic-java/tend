package work.archaic.tend.state;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.*;
import org.xml.sax.*;
import work.archaic.tend.git.GitRepository.Revision;
import work.archaic.tend.state.DesiredState.*;

/** Secure XML/XSD reader with semantic validation before any external mutation. */
public final class StateReader {
    private final Path schema;
    public StateReader(Path schema) { this.schema = schema; }

    public DesiredState read(Revision revision, String path) throws Exception {
        SchemaFactory schemas = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setSchema(schemas.newSchema(schema.toFile()));
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) throws SAXException { throw e; }
            public void error(SAXParseException e) throws SAXException { throw e; }
            public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        Element root = builder.parse(new ByteArrayInputStream(revision.read(path))).getDocumentElement();
        List<Secret> secrets = new ArrayList<>();
        Set<String> secretNames = new HashSet<>();
        for (Element e : children(root, "secret")) {
            unique(secretNames, e.getAttribute("name"));
            secrets.add(new Secret(e.getAttribute("name"), Integer.parseInt(e.getAttribute("bytes"))));
        }
        List<Volume> volumes = new ArrayList<>();
        Set<String> volumeNames = new HashSet<>();
        for (Element e : children(root, "volume")) {
            String pool = e.getAttribute("pool"), name = e.getAttribute("name");
            unique(volumeNames, pool + "/" + name);
            List<DesiredState.File> files = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            for (Element f : children(e, "file")) {
                unique(paths, f.getAttribute("path"));
                String source = f.getAttribute("source"), secret = f.getAttribute("secret");
                if (source.isBlank() == secret.isBlank()) throw new IOException("File requires exactly one source or secret");
                if (!secret.isBlank() && !secretNames.contains(secret)) throw new IOException("Undeclared secret reference");
                String mode = f.getAttribute("mode");
                if (!secret.isBlank() && (Integer.parseInt(mode, 8) & 0077) != 0)
                    throw new IOException("Secret file must exclude group and other access");
                files.add(new DesiredState.File(f.getAttribute("path"), source.isBlank() ? null : revision.read(source),
                        secret, Integer.parseInt(f.getAttribute("uid")), Integer.parseInt(f.getAttribute("gid")), mode));
            }
            volumes.add(new Volume(pool, name, config(e), files));
        }
        List<Instance> instances = new ArrayList<>();
        Set<String> instanceNames = new HashSet<>();
        for (Element e : children(root, "instance")) {
            unique(instanceNames, e.getAttribute("name"));
            Map<String, Map<String, String>> devices = new LinkedHashMap<>();
            for (Element d : children(e, "device")) {
                String name = d.getAttribute("name");
                if (devices.containsKey(name)) throw new IOException("Duplicate device name");
                Map<String, String> properties = new LinkedHashMap<>(config(d));
                if (properties.containsKey("type")) throw new IOException("Device type belongs in its attribute");
                properties.put("type", d.getAttribute("type"));
                if (properties.get("type").equals("disk") && properties.containsKey("source") &&
                        !volumeNames.contains(properties.get("pool") + "/" + properties.get("source")))
                    throw new IOException("Disk source requires a declared custom volume");
                devices.put(name, properties);
            }
            if (devices.values().stream().noneMatch(d -> d.get("type").equals("disk") && "/".equals(d.get("path")) && d.containsKey("pool")))
                throw new IOException("Instance requires an explicit root disk and pool");
            instances.add(new Instance(e.getAttribute("name"), e.getAttribute("type"), e.getAttribute("fingerprint"),
                    e.getAttribute("state").equals("running"), config(e), devices));
        }
        return new DesiredState(root.getAttribute("project"), secrets, volumes, instances);
    }

    private static Map<String, String> config(Element parent) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element c : children(parent, "config")) for (Element e : children(c, "entry")) {
            String key = e.getAttribute("key");
            if (key.isBlank() || e.getAttribute("value").isEmpty() || key.startsWith("user.tend.") || key.startsWith("volatile."))
                throw new IOException("Reserved key or empty configuration entry");
            if (result.putIfAbsent(key, e.getAttribute("value")) != null) throw new IOException("Duplicate configuration key");
        }
        return result;
    }
    private static void unique(Set<String> values, String value) throws IOException {
        if (!values.add(value)) throw new IOException("Duplicate declaration: " + value);
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(name)) result.add(e);
        return result;
    }
}
