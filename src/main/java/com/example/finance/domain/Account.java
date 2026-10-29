package com.example.finance.domain;

import java.util.UUID;

public record Account (UUID id, String name, CurrencyCode currency) {
}
