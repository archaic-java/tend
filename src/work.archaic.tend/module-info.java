/** Offline-first Incus GitOps controller. */
module work.archaic.tend {
    requires java.xml;
    requires java.net.http;
    requires com.google.gson;
    requires work.archaic.service.catalog;
    uses work.archaic.service.logging.v02.Diagnostics;
    uses work.archaic.service.logging.v02.Log;
    exports work.archaic.tend to work.archaic.tend.test;
    exports work.archaic.tend.git to work.archaic.tend.test;
    exports work.archaic.tend.state to work.archaic.tend.test;
    exports work.archaic.tend.incus to work.archaic.tend.test;
    exports work.archaic.tend.secrets to work.archaic.tend.test;
}
