package org.swisspush.gateleen.validation;

import java.util.Objects;

public class SchemaLocation {

    private final String schemaLocation;
    private final Integer keepInMemory;

    public SchemaLocation(String schemaLocation, Integer keepInMemory) {
        this.schemaLocation = schemaLocation;
        this.keepInMemory = keepInMemory;
    }

    public String schemaLocation() {
        return schemaLocation;
    }

    public Integer keepInMemory() {
        return keepInMemory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SchemaLocation that = (SchemaLocation) o;

        if (!schemaLocation.equals(that.schemaLocation)) return false;
        return Objects.equals(keepInMemory, that.keepInMemory);
    }

    @Override
    public int hashCode() {
        int result = schemaLocation.hashCode();
        result = 31 * result + (keepInMemory != null ? keepInMemory.hashCode() : 0);
        return result;
    }
}
