CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(254) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id         uuid PRIMARY KEY,
    user_id    uuid          NOT NULL REFERENCES users (id),
    name       varchar(100)  NOT NULL,
    currency   char(3)       NOT NULL CHECK (currency IN ('BYN', 'USD', 'EUR')),
    balance    numeric(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    version    bigint        NOT NULL DEFAULT 0,
    created_at timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

CREATE TABLE categories (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid REFERENCES users (id),
    name    varchar(50) NOT NULL,
    type    varchar(8)  NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    UNIQUE NULLS NOT DISTINCT (user_id, name, type)
);

CREATE TABLE operations (
    id                uuid PRIMARY KEY,
    account_id        uuid          NOT NULL REFERENCES accounts (id),
    type              varchar(8)    NOT NULL CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    amount            numeric(19,2) NOT NULL CHECK (amount > 0 AND amount <= 1000000),
    category_id       bigint REFERENCES categories (id),
    target_account_id uuid REFERENCES accounts (id),
    description       varchar(255),
    occurred_at       timestamptz   NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CHECK ((type = 'TRANSFER') = (target_account_id IS NOT NULL)),
    CHECK (type = 'TRANSFER' OR category_id IS NOT NULL)
);
