-- 步骤 1：创建演示表（幂等，支持重跑）
CREATE TABLE IF NOT EXISTS release_demo_audit (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    note       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
