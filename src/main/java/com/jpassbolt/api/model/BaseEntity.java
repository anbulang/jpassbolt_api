package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    @Column(name = "modified", nullable = false)
    private LocalDateTime modified;

    @PrePersist
    protected void onCreate() {
        created = LocalDateTime.now();
        modified = LocalDateTime.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        modified = LocalDateTime.now();
    }
}
