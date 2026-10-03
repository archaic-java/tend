/** Explicitly selected integration suite; never included in the offline test launcher. */
module work.archaic.tend.integration {
    requires work.archaic.service.catalog;
    exports work.archaic.tend.integration to work.archaic.minau;
}
