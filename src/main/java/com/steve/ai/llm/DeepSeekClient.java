package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for DeepSeek API — OpenAI-compatible chat completions.
 *
 * @see <a href="https://api-docs.deepseek.com/zh-cn/">DeepSeek API Docs</a>
 */
public class DeepSeekClient {
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";

    private final HttpClient client;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public DeepSeekClient() {
        this.apiKey = resolveApiKey();
        this.apiUrl = SteveConfig.DEEPSEEK_BASE_URL.get();
        this.model = SteveConfig.DEEPSEEK_MODEL.get();
        this.maxTokens = SteveConfig.MAX_TOKENS.get();
        this.temperature = SteveConfig.TEMPERATURE.get();
        this.client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static String resolveApiKey() {
        String deepseekKey = SteveConfig.DEEPSEEK_API_KEY.get();
        if (deepseekKey != null && !deepseekKey.isEmpty()) {
            return deepseekKey;
        }
        return SteveConfig.OPENAI_API_KEY.get();
    }

    public String sendRequest(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            SteveMod.LOGGER.error("DeepSeek API key is not set in the config.");
            return null;
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.addProperty("temperature", temperature);

        String endpoint = apiUrl.endsWith("/chat/completions")
            ? apiUrl
            : apiUrl.replaceAll("/$", "") + "/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                return jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            } else {
                SteveMod.LOGGER.error("DeepSeek API request failed: {}", response.statusCode());
                SteveMod.LOGGER.error("Response body: {}", response.body());
                return null;
            }
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error sending request to DeepSeek API", e);
            return null;
        }
    }
}
