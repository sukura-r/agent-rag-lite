package com.example.agentrag.model;

public interface ChatModel {

    String answer(String systemPrompt, String userPrompt);

    String mode();
}
