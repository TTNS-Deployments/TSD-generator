package com.ttns.tsdgenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FormData {

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("date")
    private String date;

    @JsonProperty("quote_id")
    private String quoteId;

    @JsonProperty("am_name")
    private String amName;

    @JsonProperty("am_email")
    private String amEmail;

    @JsonProperty("sa_name")
    private String saName;

    @JsonProperty("sa_email")
    private String saEmail;

    @JsonProperty("spoc_name")
    private String spocName;

    @JsonProperty("spoc_role")
    private String spocRole;

    @JsonProperty("spoc_email")
    private String spocEmail;

    @JsonProperty("region")
    private String region;

    @JsonProperty("num_users")
    private int numUsers;

    @JsonProperty("fslogix_size")
    private int fslogixSize;

    // Optional fields
    @JsonProperty("pss_name")
    private String pssName = "";

    @JsonProperty("pss_email")
    private String pssEmail = "";

    @JsonProperty("tenant_id")
    private String tenantId = "";

    @JsonProperty("domain_name")
    private String domainName = "";

    @JsonProperty("pain_point")
    private String painPoint = "";

    @JsonProperty("workload_type")
    private String workloadType = "";

    @JsonProperty("applications")
    private String applications = "";

    @JsonProperty("ad_setup_type")
    private String adSetupType = "";

    @JsonProperty("compliance_req")
    private String complianceReq = "";

    // Getters and Setters
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String quoteId) { this.quoteId = quoteId; }

    public String getAmName() { return amName; }
    public void setAmName(String amName) { this.amName = amName; }

    public String getAmEmail() { return amEmail; }
    public void setAmEmail(String amEmail) { this.amEmail = amEmail; }

    public String getSaName() { return saName; }
    public void setSaName(String saName) { this.saName = saName; }

    public String getSaEmail() { return saEmail; }
    public void setSaEmail(String saEmail) { this.saEmail = saEmail; }

    public String getSpocName() { return spocName; }
    public void setSpocName(String spocName) { this.spocName = spocName; }

    public String getSpocRole() { return spocRole; }
    public void setSpocRole(String spocRole) { this.spocRole = spocRole; }

    public String getSpocEmail() { return spocEmail; }
    public void setSpocEmail(String spocEmail) { this.spocEmail = spocEmail; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getNumUsers() { return numUsers; }
    public void setNumUsers(int numUsers) { this.numUsers = numUsers; }

    public int getFslogixSize() { return fslogixSize; }
    public void setFslogixSize(int fslogixSize) { this.fslogixSize = fslogixSize; }

    public String getPssName() { return pssName != null ? pssName : ""; }
    public void setPssName(String pssName) { this.pssName = pssName != null ? pssName : ""; }

    public String getPssEmail() { return pssEmail != null ? pssEmail : ""; }
    public void setPssEmail(String pssEmail) { this.pssEmail = pssEmail != null ? pssEmail : ""; }

    public String getTenantId() { return tenantId != null ? tenantId : ""; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId != null ? tenantId : ""; }

    public String getDomainName() { return domainName != null ? domainName : ""; }
    public void setDomainName(String domainName) { this.domainName = domainName != null ? domainName : ""; }

    public String getPainPoint() { return painPoint != null ? painPoint : ""; }
    public void setPainPoint(String painPoint) { this.painPoint = painPoint != null ? painPoint : ""; }

    public String getWorkloadType() { return workloadType != null ? workloadType : ""; }
    public void setWorkloadType(String workloadType) { this.workloadType = workloadType != null ? workloadType : ""; }

    public String getApplications() { return applications != null ? applications : ""; }
    public void setApplications(String applications) { this.applications = applications != null ? applications : ""; }

    public String getAdSetupType() { return adSetupType != null ? adSetupType : ""; }
    public void setAdSetupType(String adSetupType) { this.adSetupType = adSetupType != null ? adSetupType : ""; }

    public String getComplianceReq() { return complianceReq != null ? complianceReq : ""; }
    public void setComplianceReq(String complianceReq) { this.complianceReq = complianceReq != null ? complianceReq : ""; }
}
