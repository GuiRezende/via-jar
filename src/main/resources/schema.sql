CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS viajar;

CREATE TABLE IF NOT EXISTS viajar.base_conhecimento (
    id VARCHAR(150) PRIMARY KEY,
    pergunta TEXT NOT NULL,
    resposta TEXT NOT NULL,
    embedding_pergunta VECTOR(384) NOT NULL,
    embedding_id VECTOR(384) NOT NULL
);

CREATE TABLE IF NOT EXISTS viajar.cache_semantico (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    texto TEXT NOT NULL,
    assinatura TEXT NOT NULL,
    resposta JSONB NOT NULL,
    embedding VECTOR(384) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cache_assinatura
    ON viajar.cache_semantico (assinatura);

ALTER TABLE viajar.cache_semantico
    ADD COLUMN IF NOT EXISTS modelo_embeddings TEXT NOT NULL,
    ADD COLUMN IF NOT EXISTS revisao_modelo TEXT NOT NULL;

ALTER TABLE viajar.base_conhecimento
    ADD COLUMN IF NOT EXISTS ordem INTEGER NOT NULL DEFAULT 0;


CREATE TABLE IF NOT EXISTS viajar.historico_avaliacoes (
    id UUID PRIMARY KEY,
    id_processamento UUID NOT NULL,
    id_sessao TEXT NOT NULL,
    data_hora TIMESTAMPTZ NOT NULL,
    origem VARCHAR(20) NOT NULL,
    tentativa INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    nota NUMERIC(5,2),
    registro JSONB NOT NULL,

    CONSTRAINT ck_avaliacao_status
        CHECK (status IN ('APROVADO', 'REPROVADO', 'INDISPONIVEL')),

    CONSTRAINT ck_avaliacao_origem
        CHECK (origem IN ('GERACAO', 'CACHE')),

    CONSTRAINT ck_avaliacao_nota
        CHECK (nota BETWEEN 0 AND 100),

    CONSTRAINT ck_avaliacao_tentativa
        CHECK (tentativa >= 1)
);

CREATE INDEX IF NOT EXISTS idx_avaliacao_status_data
    ON viajar.historico_avaliacoes (status, data_hora DESC);

CREATE INDEX IF NOT EXISTS idx_avaliacao_processamento
    ON viajar.historico_avaliacoes (id_processamento, tentativa);