package com.ttns.tsdgenerator.service;

import com.google.gson.*;
import com.ttns.tsdgenerator.model.FormData;
import com.ttns.tsdgenerator.model.LlmContent;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    @Value("${prompt.path}")
    private String promptPath;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();

    public Map<String, String> buildPrompt(FormData formData) throws IOException {
        String systemPrompt = new String(Files.readAllBytes(Paths.get(promptPath)));

        StringBuilder userMessage = new StringBuilder();
        userMessage.append("Generate TSD sections for the following customer:\n");
        userMessage.append("customer_name: ").append(formData.getCustomerName()).append("\n");
        userMessage.append("num_users: ").append(formData.getNumUsers()).append("\n");
        userMessage.append("existing_licenses: ").append(formData.getNumUsers()).append("\n");

        if (!formData.getPainPoint().isEmpty()) {
            userMessage.append("pain_point: ").append(formData.getPainPoint()).append("\n");
        }
        if (!formData.getWorkloadType().isEmpty()) {
            userMessage.append("workload_type: ").append(formData.getWorkloadType()).append("\n");
        }
        if (!formData.getApplications().isEmpty()) {
            userMessage.append("applications: ").append(formData.getApplications()).append("\n");
        }
        if (!formData.getAdSetupType().isEmpty()) {
            userMessage.append("ad_setup_type: ").append(formData.getAdSetupType()).append("\n");
        }
        if (!formData.getComplianceReq().isEmpty()) {
            userMessage.append("compliance_req: ").append(formData.getComplianceReq()).append("\n");
        }

        Map<String, String> result = new HashMap<>();
        result.put("systemPrompt", systemPrompt);
        result.put("userMessage", userMessage.toString());
        return result;
    }

    public LlmContent generateContent(Map<String, String> promptDict) throws IOException {
        String systemPrompt = promptDict.get("systemPrompt");
        String userMessage = promptDict.get("userMessage");

        // Attempt 1
        String responseText = callGeminiApi(systemPrompt, userMessage);
        LlmContent content = tryParseContent(responseText, false);
        if (content != null) return content;

        // Clean markdown fences and retry parse
        content = tryParseContent(responseText, true);
        if (content != null) return content;

        // Attempt 2 with stronger instruction
        String userMessage2 = userMessage + "\nIMPORTANT: Return ONLY a valid JSON object. No markdown. No extra text.";
        responseText = callGeminiApi(systemPrompt, userMessage2);
        content = tryParseContent(responseText, true);
        if (content != null) return content;

        throw new RuntimeException("Failed to parse valid LLM response after 2 attempts. Last response: " + responseText);
    }

    private String callGeminiApi(String systemPrompt, String userMessage) throws IOException {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                geminiModel + ":generateContent?key=" + geminiApiKey;

        JsonObject requestBody = new JsonObject();

        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", systemPrompt);
        systemParts.add(systemPart);
        systemInstruction.add("parts", systemParts);
        requestBody.add("system_instruction", systemInstruction);

        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userMessage);
        userParts.add(userPart);
        userContent.add("parts", userParts);
        contents.add(userContent);
        requestBody.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.3);
        generationConfig.addProperty("responseMimeType", "application/json");
        requestBody.add("generationConfig", generationConfig);

        RequestBody body = RequestBody.create(
                gson.toJson(requestBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Gemini API error: " + response.code() + " " + response.message() + " — " + errBody);
            }
            if (response.body() == null) {
                throw new IOException("Empty response body from Gemini API");
            }
            String responseBodyStr = response.body().string();
            JsonObject responseJson = JsonParser.parseString(responseBodyStr).getAsJsonObject();
            return responseJson
                    .getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        }
    }

    private LlmContent tryParseContent(String text, boolean cleanFences) {
        String toParse = text;
        if (cleanFences) {
            toParse = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }
        try {
            JsonObject obj = JsonParser.parseString(toParse).getAsJsonObject();

            String problemStatement = obj.has("problem_statement") ?
                    obj.get("problem_statement").getAsString() : null;
            String existingArchitecture = obj.has("existing_architecture") ?
                    obj.get("existing_architecture").getAsString() : null;

            List<String> currentInfra = null;
            if (obj.has("current_infra")) {
                currentInfra = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("current_infra")) {
                    currentInfra.add(el.getAsString());
                }
            }

            List<String> successCriteria = null;
            if (obj.has("success_criteria")) {
                successCriteria = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("success_criteria")) {
                    successCriteria.add(el.getAsString());
                }
            }

            if (problemStatement == null || existingArchitecture == null ||
                    currentInfra == null || successCriteria == null) {
                return null;
            }

            LlmContent content = new LlmContent();
            content.setProblemStatement(problemStatement);
            content.setExistingArchitecture(existingArchitecture);
            content.setCurrentInfra(currentInfra);
            content.setSuccessCriteria(successCriteria);
            return content;

        } catch (Exception e) {
            return null;
        }
    }
}
