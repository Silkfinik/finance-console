# Gradle vs Maven

Сравнение двух сборок одного проекта: `build.gradle.kts` + `gradle/libs.versions.toml` и `pom.xml` (ветка `maven-variant`). Обе собирают одинаковый `finance-console-0.1.0.jar` и запускают те же 26 тестов.

| Критерий | Gradle 9.6.0 | Maven 3.9.11 |
|---|---|---|
| Синтаксис | Kotlin DSL (`build.gradle.kts`) - код, 48 строк + 18 строк TOML-каталога версий | XML (`pom.xml`), 178 строк; декларативно, но многословно |
| Полная сборка (`clean build` / `clean verify`) | 6.81 / 7.42 / 8.95 с, медиана **7.42 с** | 13.41 / 13.57 / 13.79 с, медиана **13.57 с** |
| Повторная сборка без изменений (`build` / `verify`) | 1.75 / 1.82 / 1.97 с, медиана **1.82 с** - все задачи `UP-TO-DATE`, тесты не перезапускаются | 11.58 / 13.26 / 15.64 с, медиана **13.26 с** - компиляция пропускается, но тесты и упаковка jar выполняются заново |
| Объявление зависимостей | Одна строка на зависимость: `implementation(libs.jackson.databind)`; координаты и версии - в `libs.versions.toml`, BOM через `platform(...)` | Блок `<dependency>` из 3–5 строк на каждую; версии - в `<properties>`, BOM через `<dependencyManagement>` с `<scope>import</scope>` |
| Подключение плагинов | `plugins { id("java"); application }` - `java` и `application` встроены, дают компиляцию, тесты, jar и `run` без настройки; сторонние - `alias(libs.plugins.x)` | Плагины жизненного цикла работают по умолчанию, но версии нужно фиксировать вручную (`<pluginManagement>`); всё остальное - отдельный `<plugin>` с `<executions>`/`<configuration>` (enforcer, exec, dependency + surefire для Mockito-агента) |

## Условия замера

- Дата: 2026-11-02; Fedora, OpenJDK 25.0.4.1.
- Каждая команда запускалась 3 раза подряд после одного прогревочного запуска (не учтён), зависимости уже в локальном кеше.
- build cache и configuration cache не включены.
