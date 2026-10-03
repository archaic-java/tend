module work.archaic.tend.test {
    requires work.archaic.tend;
    requires work.archaic.service.catalog;
    requires com.google.gson;
    requires java.net.http;
    requires jdk.httpserver;
    exports work.archaic.tend.test to work.archaic.minau;
}
