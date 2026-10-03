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

    public DesiredState read(Revision revision, String path) throws IOException, InterruptedException {
        try { return readRevision(revision, path); }
        catch (NumberFormatException e) { throw new StateException("Numeric attribute exceeds supported bounds", e); }
    }
    private DesiredState readRevision(Revision revision, String path) throws IOException, InterruptedException {
        Element root = document(revision.read(path));
        List<Secret> secrets = new ArrayList<>();
        Set<String> secretNames = new HashSet<>();
        for (Element e : children(root, "secret")) {
            unique(secretNames, e.getAttribute("name"));
            if (!e.getAttribute("kind").equals("random") && e.getAttributeNode("bytes").getSpecified())
                throw new StateException("Only random secrets accept bytes");
            secrets.add(new Secret(e.getAttribute("name"), Integer.parseInt(e.getAttribute("bytes")),
                    e.getAttribute("kind"), e.getAttribute("source")));
        }
        var volumes = volumes(root, revision, secretNames);
        Set<String> volumeNames = new HashSet<>();
        volumes.forEach(v -> volumeNames.add(v.pool() + "/" + v.name()));
        var instances = instances(root, volumeNames);
        var desired = new DesiredState(root.getAttribute("project"), secrets, volumes, instances,
                configurations(root, revision), gateway(root), ingresses(root), egresses(root));
        new ResourceCompiler().compile(desired); // Resolve references and reject policy conflicts before external mutation.
        return desired;
    }

    private static List<Volume> volumes(Element root, Revision revision, Set<String> secrets) throws IOException, InterruptedException {
        List<Volume> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element e : children(root, "volume")) {
            String pool = e.getAttribute("pool"), name = e.getAttribute("name");
            unique(names, pool + "/" + name);
            result.add(new Volume(pool, name, config(e), files(e, revision, secrets)));
        }
        return result;
    }
    private static List<DesiredState.File> files(Element volume, Revision revision, Set<String> secrets) throws IOException, InterruptedException {
        List<DesiredState.File> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (Element file : children(volume, "file")) {
            unique(paths, file.getAttribute("path"));
            String source = file.getAttribute("source"), secret = file.getAttribute("secret");
            if (source.isBlank() == secret.isBlank()) throw new StateException("File requires exactly one source or secret");
            if (!secret.isBlank() && !secrets.contains(secret)) throw new StateException("Undeclared secret reference");
            String mode = file.getAttribute("mode");
            if (!secret.isBlank() && (Integer.parseInt(mode, 8) & 0077) != 0) throw new StateException("Secret file must exclude group and other access");
            result.add(new DesiredState.File(file.getAttribute("path"), source.isBlank() ? null : revision.read(source), secret,
                    integer(file, "uid"), integer(file, "gid"), mode));
        }
        return result;
    }
    private static List<Instance> instances(Element root, Set<String> volumes) throws StateException {
        List<Instance> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element e : children(root, "instance")) {
            unique(names, e.getAttribute("name"));
            var devices = devices(e, volumes);
            if (devices.values().stream().noneMatch(d -> "disk".equals(d.get("type")) && "/".equals(d.get("path")) && d.containsKey("pool")))
                throw new StateException("Instance requires an explicit root disk and pool");
            result.add(new Instance(e.getAttribute("name"), e.getAttribute("type"), e.getAttribute("fingerprint"),
                    e.getAttribute("state").equals("running"), config(e), devices, mounts(e)));
        }
        return result;
    }
    private static Map<String, Map<String, String>> devices(Element instance, Set<String> volumes) throws StateException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Element device : children(instance, "device")) {
            var properties = new LinkedHashMap<>(config(device));
            if (properties.containsKey("type")) throw new StateException("Device type belongs in its attribute");
            properties.put("type", device.getAttribute("type"));
            if ("disk".equals(properties.get("type")) && properties.containsKey("source") &&
                    !volumes.contains(properties.get("pool") + "/" + properties.get("source")))
                throw new StateException("Disk source requires a declared custom volume");
            if (result.putIfAbsent(device.getAttribute("name"), properties) != null) throw new StateException("Duplicate device name");
        }
        return result;
    }

    private static List<Configuration> configurations(Element root, Revision revision) throws IOException, InterruptedException {
        List<Configuration> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element element : children(root, "configuration")) {
            unique(names, element.getAttribute("name"));
            List<DesiredState.File> files = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            for (Element file : children(element, "file")) {
                unique(paths, file.getAttribute("path"));
                files.add(new DesiredState.File(file.getAttribute("path"), revision.read(file.getAttribute("source")), "", 0, 0, "0644"));
            }
            result.add(new Configuration(element.getAttribute("name"), files));
        }
        return result;
    }
    private static List<Mount> mounts(Element instance) {
        return children(instance, "mount").stream().map(e -> new Mount(e.getAttribute("name"), e.getAttribute("configuration"),
                e.getAttribute("secret"), e.getAttribute("pool"), e.getAttribute("path"), integer(e, "uid"), integer(e, "gid"), e.getAttribute("mode"))).toList();
    }
    private static Gateway gateway(Element root) {
        var elements = children(root, "ingress-gateway");
        if (elements.isEmpty()) return null;
        var e = elements.getFirst();
        return new Gateway(e.getAttribute("instance"), e.getAttribute("pool"), e.getAttribute("path"), e.getAttribute("authorization-instance"),
                e.getAttribute("authorization-device"), integer(e, "authorization-port"), e.getAttribute("authorization-path"), integer(e, "uid"), integer(e, "gid"), integer(e, "authorization-uid"), integer(e, "authorization-gid"));
    }
    private static List<Ingress> ingresses(Element root) throws StateException {
        List<Ingress> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element e : children(root, "ingress")) {
            unique(names, e.getAttribute("name"));
            var authorization = children(e, "authorization");
            var groups = authorization.isEmpty() ? List.<String>of() : children(authorization.getFirst(), "group").stream().map(g -> g.getAttribute("name")).toList();
            result.add(new Ingress(e.getAttribute("name"), e.getAttribute("host"), e.getAttribute("instance"), e.getAttribute("device"),
                    integer(e, "port"), !children(e, "public").isEmpty(), authorization.isEmpty() ? "bypass" : authorization.getFirst().getAttribute("policy"), groups));
        }
        return result;
    }
    private static List<Egress> egresses(Element root) throws StateException {
        List<Egress> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Element e : children(root, "egress")) {
            unique(names, e.getAttribute("name"));
            var rules = children(e, "allow").stream().map(r -> new Rule(r.getAttribute("address"), r.getAttribute("protocol"), integer(r, "port"))).toList();
            result.add(new Egress(e.getAttribute("name"), e.getAttribute("instance"), e.getAttribute("device"), rules));
        }
        return result;
    }
    private static int integer(Element element, String attribute) { return Integer.parseInt(element.getAttribute(attribute)); }

    private Element document(byte[] bytes) throws StateException {
        try {
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
            return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (SAXException | javax.xml.parsers.ParserConfigurationException | IOException e) {
            throw new StateException("Invalid XML or schema", e);
        }
    }

    private static Map<String, String> config(Element parent) throws StateException {
        Map<String, String> result = new LinkedHashMap<>();
        var containers = children(parent, "config");
        if (containers.isEmpty()) return result;
        for (Element e : children(containers.getFirst(), "entry")) {
            String key = e.getAttribute("key");
            if (key.isBlank() || e.getAttribute("value").isEmpty() || key.startsWith("user.tend.") || key.startsWith("volatile."))
                throw new StateException("Reserved key or empty configuration entry");
            if (result.putIfAbsent(key, e.getAttribute("value")) != null) throw new StateException("Duplicate configuration key");
        }
        return result;
    }
    private static void unique(Set<String> values, String value) throws StateException {
        if (!values.add(value)) throw new StateException("Duplicate declaration: " + value);
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(name)) result.add(e);
        return result;
    }
}
