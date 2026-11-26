package com.ttns.tsdgenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * A D2 TSD request: which D2 document to build and the values for its placeholders.
 * Field keys match the template placeholders without braces, e.g. "customer_name" fills {{customer_name}}.
 */
public class D2Request {

    @JsonProperty("d2_type")
    private String d2Type;

    @JsonProperty("fields")
    private Map<String, String> fields = new HashMap<>();

    public String getD2Type() { return d2Type; }
    public void setD2Type(String d2Type) { this.d2Type = d2Type; }

    public Map<String, String> getFields() { return fields; }
    public void setFields(Map<String, String> fields) { this.fields = fields != null ? fields : new HashMap<>(); }
}
