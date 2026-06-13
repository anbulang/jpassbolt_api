package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        // 统一以 UTC 写入：序列化层会按 UTC 附加 +00:00 offset 输出 RFC3339，
        // 二者必须同一时区，否则给本地时刻硬加 offset 会得到错误时刻。
        created = LocalDateTime.now(ZoneOffset.UTC);
        modified = LocalDateTime.now(ZoneOffset.UTC);
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        modified = LocalDateTime.now(ZoneOffset.UTC);
    }
}
