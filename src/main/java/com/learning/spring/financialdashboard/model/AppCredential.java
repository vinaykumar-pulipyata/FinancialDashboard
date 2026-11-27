package com.learning.spring.financialdashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The dashboard password, stored only as a BCrypt hash. There is at most one row (id 1); no row means no password
 * has been set yet.
 */
@Entity
@Table(name = "app_credential")
public class AppCredential {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Column(nullable = false)
    private String passwordHash;

    protected AppCredential() {
    }

    public AppCredential(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
