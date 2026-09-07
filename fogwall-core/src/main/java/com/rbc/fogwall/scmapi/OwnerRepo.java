package com.rbc.fogwall.scmapi;

/** A resolved {@code owner/repo} pair — the authorization target a permission check runs against. */
public record OwnerRepo(String owner, String name) {}
