package com.ttns.tsdgenerator.model;

import java.util.List;

public class LlmContent {

    private String problemStatement;
    private List<String> currentInfra;
    private String existingArchitecture;
    private List<String> successCriteria;

    public String getProblemStatement() { return problemStatement; }
    public void setProblemStatement(String problemStatement) { this.problemStatement = problemStatement; }

    public List<String> getCurrentInfra() { return currentInfra; }
    public void setCurrentInfra(List<String> currentInfra) { this.currentInfra = currentInfra; }

    public String getExistingArchitecture() { return existingArchitecture; }
    public void setExistingArchitecture(String existingArchitecture) { this.existingArchitecture = existingArchitecture; }

    public List<String> getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(List<String> successCriteria) { this.successCriteria = successCriteria; }
}
