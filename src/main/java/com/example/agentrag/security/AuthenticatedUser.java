package com.example.agentrag.security;

public record AuthenticatedUser(String userId, Role role) {
    public enum Role { USER, ADMIN, INTERNAL }
}
