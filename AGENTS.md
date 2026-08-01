# Coding Agent Guidelines for Hop Execution History plugin

**IMPORTANT — READ FIRST**

- **Act as a Senior Software Engineer and Software Architect.** Approach software development with:
    - **Pragmatism**: Favor simple solutions over clever ones
    - **Skepticism**: Question decisions that could cause technical debt or scalability issues
    - **Efficiency**: Only challenge when it genuinely matters
- **Think before coding**: explicitly state assumptions, compare alternatives, and justify choices.
- **Simplicity first (KISS)**: overengineering and "gas factories" are strictly forbidden.
- **Surgical changes only**: touch **only** what is strictly necessary to achieve the goal.
- **Goal-driven execution**: define what success looks like *before* writing the first line of code.
- **Preserve existing comments**: never delete any existing comment **unless** you are improving its clarity or usefulness.
- **Write clear, maintainable, and well-documented code**
- **Build & test are mandatory**

## Project

Apache Hop plugin dealing with the history of pipeline and workflow executions.
Project built with Java, using Eclipse SWT and JFace as frontend, using Maven as the build system.

## Tech Stack
- **Backend:** Java 21, Lombok, Apache Hop 2.19.0-SNAPSHOT
- **Frontend:** Eclipse SWT and JFace
- **Build:** Maven
- **Testing:** JUnit 6

## Build commands

| Command | Purpose |
| --- | --- |
| `mvn clean verify` | Full build with RAT, Checkstyle, Spotless and tests |
| `mvn clean install` | Build and deploy the plugin into `${hop.home}/custom` |
| `mvn clean install -Pfast-build` | Skip tests and quality checks while iterating |

Code is formatted by `spotless:apply` (google-java-format) during the `compile` phase; never
hand-format against it.

## Project layout

```
src/assembly/assembly.xml            Hop plugin zip descriptor (plugins/misc/execution-history)
src/main/java/org/apache/hop/execution/history/    Engine side code
src/main/resources/.../messages/     i18n bundles read by BaseMessages
src/main/resources/version.xml       Plugin version, filtered at package time
tools/maven/checkstyle.xml           Checkstyle rules referenced by the Hop parent POM
```

## Critical Code Patterns

### Hop plugin conventions
- A plugin class is declared with its Hop annotation (e.g. `@ExecutionInfoLocationPlugin`,
  `@Transform`, `@Action`, `@HopPerspectivePlugin`) and discovered at runtime by scanning; keep the
  `id` stable, it is persisted in metadata and project files.
- GUI widgets are declared with `@GuiPlugin` / `@GuiWidgetElement`, labels come from
  `BaseMessages.getString(PKG, "...")`.
- Never hardcode user visible text: add a key to `messages_en_US.properties`.

### Naming Conventions
- Follow Java naming-convention best practices for Classes, Methods, Variables, Constants.
- Boolean methods: Start with `is`, `has`, `should`, `can`, (e.g., `isReadOnly()`).

### Error Handling
- Use try-with-resources for resource management
- Wrap low level failures in `HopException` with an actionable message

### Java Language Features
- Use java records for simple data carriers

### Documentation
- Javadoc for all public classes and methods – be concise
- Use `@param`, `@return`, `@throws` appropriately
- Use `{@inheritDoc}` for inherited methods
- Include usage examples for complex methods

### Utility Classes
* Mark utility classes as `final` with a private constructor
* Use static methods only
