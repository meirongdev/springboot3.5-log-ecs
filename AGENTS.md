# Repository Guidelines

Welcome to the project! This guide outlines the standards and best practices for contributing to the codebase. Please review this document thoroughly before submitting any changes.

## 🧩 Project Structure & Module Organization
The codebase follows a standard Spring Boot layout.
- **Source Code:** Core business logic resides in `src/main/java/` within module-specific packages.
- **Tests:** Unit and integration tests are located in `src/test/java/` and mirror the package structure of the source code.
- **Assets/Configuration:** Shared resources, such as static assets or complex YAML configurations, should be placed in the `src/main/resources/` directory.

## ⚙️ Build, Test, and Development Commands
Use the following commands for standard development workflows.
- **Build:** `./mvnw clean package` - Compiles the entire project and creates a runnable artifact.
- **Test:** `./mvnw test` - Executes all unit and integration tests using Maven.
- **Run Locally:** `./mvnw spring-boot:run` - Starts the application embedded server for quick local testing.

## ✍️ Coding Style & Naming Conventions
Maintaining consistency is crucial.
- **Indentation:** Use 4 spaces. Tabs are forbidden.
- **Language Style:** Adhere to standard Java/Spring Boot conventions (e.g., Javadoc usage, camelCase for variables).
- **Naming Patterns:** Classes should be PascalCase (e.g., `UserService`). Methods should be camelCase (e.g., `getUserDetails()`). Avoid using Hungarian notation.
- **Linting:** We utilize `spotbugs` (integrated via Maven) for static analysis. Ensure all newly written code passes linting checks.

## 🧪 Testing Guidelines
All features must be accompanied by appropriate tests.
- **Framework:** We use JUnit 5 for unit tests and Spring Test for integration tests.
- **Coverage:** Aim for a minimum of 80% test coverage on new business logic.
- **Test Naming:** Name tests descriptively, e.g., `testUserCreationSuccess()` or `shouldReturnNotFoundOnInvalidId()`.
- **Running Tests:** Run targeted tests using `./mvnw test -Dtest=MyServiceTest` to improve feedback speed.

## 🤝 Commit & Pull Request Guidelines
Please follow these guidelines when submitting code.
- **Commit Messages:** Use the Conventional Commits specification: `type(scope): description`. Examples: `feat(auth): added password hashing` or `fix(api): corrected user lookup endpoint`.
- **PR Requirements:** Every PR *must* include:
    1. A detailed description explaining *what* was changed and *why*.
    2. A link to the corresponding issue (e.g., Closes #123).
    3. Confirmation that all local tests pass locally before requesting review.

## 🛡️ Security & Configuration Tips
- **Secrets:** Never hardcode sensitive credentials (API keys, DB passwords). Use Spring Boot `application.yml` profiles or environment variables exclusively.
- **Logging:** Log only necessary context. Avoid logging passwords or session tokens in plain text.
