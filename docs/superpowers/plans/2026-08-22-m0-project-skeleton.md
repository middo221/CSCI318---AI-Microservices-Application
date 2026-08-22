# ConfHub M0 — Project Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the ConfHub multi-module Maven repository with a working, tested vertical slice — creating and reading a draft event — plus Kafka running locally and every project artefact the CSCI318 submission requires.

**Architecture:** A Maven multi-module repository under a Spring Boot parent. `common-events` is a dependency-free Published Language module. `event-catalog-service` is implemented end-to-end through all four architectural layers (api → application → domain → infrastructure) to establish the pattern every later service copies. The remaining five services are created as runnable skeletons. Kafka runs in Docker but is not yet wired into any service — that is M1.

**Tech Stack:** Java 21, Maven 3.9.16 (via wrapper), Spring Boot 3.5.15, Spring Cloud 2025.0.3, LangChain4j 1.19.0, H2, Apache Kafka 4.3.1 (Docker), JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md`

## Global Constraints

- **JDK 21 exactly.** Not 17, not 24. Enforced by `maven-enforcer-plugin` with range `[21,22)`.
- **Group ID:** `au.edu.uow.csci318`. **Base package:** `au.edu.uow.csci318.conference`.
- **Version:** `0.1.0-SNAPSHOT` for every module.
- **Dependency allowlist.** Only these may appear in any `pom.xml`. Anything else requires the subject coordinator's permission and is out of scope for this plan:
  `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-test`, `com.h2database:h2`, `spring-cloud-stream`, `spring-cloud-stream-binder-kafka`, `spring-cloud-stream-binder-kafka-streams`, `spring-cloud-stream-test-binder`, `dev.langchain4j:*`, `org.junit.jupiter:junit-jupiter`, `org.assertj:assertj-core`, and the in-repo `au.edu.uow.csci318:common-events`.
- **No Lombok, no MapStruct, no springdoc, no Testcontainers.** Use Java 21 `record` types and hand-written mappers.
- **Layer packages are fixed:** `api`, `application`, `domain`, `infrastructure`. The domain layer must not import `org.springframework` except for JPA mapping annotations on aggregate roots.
- **`./mvnw clean test` must pass with no Docker container running and no network access** (beyond Maven's first dependency download).
- **Ports:** catalog 8081, registration 8082, payment 8083, ticketing 8084, analytics 8085, concierge 8086. Kafka 9092, Kafka UI 8080.
- **Never commit secrets.** `.env` is gitignored; `.env.example` is committed.
- Commit after every task. Commit messages use Conventional Commits (`feat:`, `chore:`, `docs:`, `test:`).

---

## File Structure

| Path | Responsibility |
| :--- | :--- |
| `pom.xml` | Aggregator: modules, dependency management, JDK enforcement |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven wrapper so nobody installs Maven |
| `.gitignore`, `.env.example` | Repo hygiene, secret template |
| `docker-compose.yml` | Kafka (KRaft) + Kafka UI |
| `common-events/` | Published Language: `DomainEvent` and shared event records |
| `event-catalog-service/` | Fully implemented vertical slice (FR-01, read-by-id) |
| `registration-service/` … `concierge-service/` | Runnable skeletons |
| `specs/` | University-reference-style specification files |
| `agent-prompts/dev-agent.md` | Development agent prompt |
| `docs/meetings/`, `docs/ai-collaboration-log.md` | Assessment artefacts (3 marks + Section 5) |
| `README.md` | Configuration, running instructions, sample inputs |

**Package layout inside `event-catalog-service`:**

| Path under `src/main/java/au/edu/uow/csci318/conference/catalog/` | Responsibility |
| :--- | :--- |
| `EventCatalogApplication.java` | Spring Boot entry point |
| `api/EventController.java` | HTTP endpoints |
| `api/GlobalExceptionHandler.java` | Error → HTTP status mapping |
| `api/dto/CreateEventRequest.java`, `api/dto/EventResponse.java` | Wire contracts |
| `application/EventApplicationService.java` | Use-case orchestration, transactions |
| `application/command/CreateEventCommand.java` | Presentation-independent input |
| `domain/model/Event.java`, `Venue.java`, `DateRange.java`, `EventStatus.java` | Aggregate and value objects |
| `domain/repository/EventRepository.java` | Persistence port |
| `domain/EventNotFoundException.java` | Domain exception |
| `infrastructure/persistence/EventJpaRepository.java`, `JpaEventRepository.java` | Persistence adapter |

---

### Task 1: Toolchain and repository hygiene

Establishes JDK 21, the committed Maven wrapper, and the ignore rules. Nothing else can be built until `./mvnw -v` reports Maven 3.9.16 on Java 21.

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.jar` (extracted from the official distribution)

**Interfaces:**
- Consumes: nothing
- Produces: a working `./mvnw` command used by every later task

- [ ] **Step 1: Install Eclipse Temurin JDK 21**

The machine currently has JDK 24 only, which the enforcer in Task 2 will reject. Install 21 alongside it — do not uninstall 24.

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e
```

- [ ] **Step 2: Point JAVA_HOME at JDK 21 and verify**

```powershell
$jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter 'jdk-21*' | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
$env:JAVA_HOME = $jdk
& "$env:JAVA_HOME\bin\java.exe" -version
```

Expected: `openjdk version "21.0.x"`. If `$jdk` is empty, the install did not land in that folder — locate it with `Get-ChildItem 'C:\Program Files' -Filter 'jdk-21*' -Recurse -Depth 2` and set `JAVA_HOME` manually.

- [ ] **Step 3: Download and unpack the Maven wrapper**

The wrapper is fetched from the official Apache distribution because Maven itself is not installed, so `mvn wrapper:wrapper` cannot be used.

```bash
curl -sLO "https://repo1.maven.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/maven-wrapper-distribution-3.3.4-bin.zip"
python -c "import zipfile; zipfile.ZipFile('maven-wrapper-distribution-3.3.4-bin.zip').extractall('.')"
rm maven-wrapper-distribution-3.3.4-bin.zip mvnwDebug mvnwDebug.cmd
chmod +x mvnw
```

This yields `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.jar`.

- [ ] **Step 4: Write the wrapper properties**

Create `.mvn/wrapper/maven-wrapper.properties`:

```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar
```

- [ ] **Step 5: Verify the wrapper resolves Maven and the correct JDK**

Run: `./mvnw -v`

Expected: `Apache Maven 3.9.16` and `Java version: 21.0.x`. The first run downloads Maven, which takes a minute. If it reports Java 24, `JAVA_HOME` was not picked up — reopen the terminal.

- [ ] **Step 6: Write `.gitignore`**

```gitignore
# Build output
target/
!.mvn/wrapper/maven-wrapper.jar

# Secrets — never commit
.env

# IDEs
.idea/
*.iml
*.iws
*.ipr
.vscode/
.classpath
.project
.settings/
.factorypath
.apt_generated/

# OS
.DS_Store
Thumbs.db

# Logs and local state
*.log
/kafka-streams-state/
```

- [ ] **Step 7: Write `.env.example`**

```bash
# Copy to .env and fill in. .env is gitignored and must never be committed.
# Free key from https://aistudio.google.com/apikey
GEMINI_API_KEY=your-key-here
```

- [ ] **Step 8: Commit**

```bash
git add .gitignore .env.example mvnw mvnw.cmd .mvn
git commit -m "chore: add Maven wrapper, gitignore and env template"
```

---

### Task 2: Root aggregator POM and the `common-events` module

The Published Language module. `DomainEvent.eventType()` derives the Kafka topic name from the record's class name, which is how the §6.1 topic-naming convention is enforced in code rather than by discipline.

**Files:**
- Create: `pom.xml`
- Create: `common-events/pom.xml`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/DomainEvent.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/DomainEventTest.java`

**Interfaces:**
- Consumes: the Maven wrapper from Task 1
- Produces:
  - `au.edu.uow.csci318:conference-platform:0.1.0-SNAPSHOT` (pom packaging, aggregator)
  - `au.edu.uow.csci318:common-events:0.1.0-SNAPSHOT`
  - `interface DomainEvent { String eventId(); Instant occurredAt(); default String eventType(); static String toKebabCase(String); }`

- [ ] **Step 1: Write the root aggregator POM**

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.15</version>
    <relativePath/>
  </parent>

  <groupId>au.edu.uow.csci318</groupId>
  <artifactId>conference-platform</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>ConfHub Conference and Event Registration Platform</name>
  <description>CSCI318 Spring 2026 group project</description>

  <modules>
    <module>common-events</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-cloud.version>2025.0.3</spring-cloud.version>
    <langchain4j.version>1.19.0</langchain4j.version>
    <langchain4j-spring.version>1.19.0-beta29</langchain4j-spring.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-bom</artifactId>
        <version>${langchain4j.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>au.edu.uow.csci318</groupId>
        <artifactId>common-events</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>enforce-jdk-21</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion>
                  <version>[21,22)</version>
                  <message>ConfHub requires JDK 21 (CSCI318 specification). Set JAVA_HOME to a Temurin 21 installation.</message>
                </requireJavaVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Note: `maven-enforcer-plugin` is a *build* plugin, not an application dependency, so it does not consume the dependency allowlist. It exists to make the JDK-mismatch risk in spec §15 fail loudly instead of silently.

- [ ] **Step 2: Write the `common-events` POM**

Create `common-events/pom.xml`. Deliberately no Spring on the main classpath — this module is plain Java.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>au.edu.uow.csci318</groupId>
    <artifactId>conference-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>common-events</artifactId>
  <name>ConfHub Published Language</name>
  <description>Domain event records shared across bounded contexts. No Spring, no JPA, no behaviour.</description>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: Write the failing test**

Create `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/DomainEventTest.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventTest {

    record SessionCapacityChanged(String eventId, Instant occurredAt) implements DomainEvent {}

    record EventPublished(String eventId, Instant occurredAt) implements DomainEvent {}

    @Test
    void derivesMultiWordTopicNameFromClassName() {
        DomainEvent event = new SessionCapacityChanged("evt-1", Instant.EPOCH);

        assertThat(event.eventType()).isEqualTo("session-capacity-changed");
    }

    @Test
    void derivesTwoWordTopicNameFromClassName() {
        DomainEvent event = new EventPublished("evt-2", Instant.EPOCH);

        assertThat(event.eventType()).isEqualTo("event-published");
    }

    @Test
    void exposesIdentityAndTimestamp() {
        Instant when = Instant.parse("2026-08-22T10:15:30Z");
        DomainEvent event = new EventPublished("evt-3", when);

        assertThat(event.eventId()).isEqualTo("evt-3");
        assertThat(event.occurredAt()).isEqualTo(when);
    }

    @Test
    void rejectsBlankClassName() {
        assertThatThrownBy(() -> DomainEvent.toKebabCase("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsNullClassName() {
        assertThatThrownBy(() -> DomainEvent.toKebabCase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw -q -pl common-events test`

Expected: compilation failure — `cannot find symbol: class DomainEvent`.

- [ ] **Step 5: Write the minimal implementation**

Create `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/DomainEvent.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Implemented by every event that crosses a bounded-context boundary.
 *
 * <p>This interface is the ConfHub <em>Published Language</em>: the shared vocabulary
 * contexts agree on for inter-service messages. Implementations live in this module and
 * must be immutable records carrying data only — no behaviour, no Spring, no JPA.
 */
public interface DomainEvent {

    /** Unique identifier for this event occurrence, used by consumers for idempotency. */
    String eventId();

    /** When the event occurred in the producing context. */
    Instant occurredAt();

    /**
     * The Kafka topic this event is published to, derived from the implementing record's
     * simple class name: {@code SessionCapacityChanged} becomes
     * {@code session-capacity-changed}.
     *
     * <p>Deriving the name rather than declaring it keeps the topic naming convention in
     * spec section 6.1 enforced by the compiler instead of by discipline.
     */
    default String eventType() {
        return toKebabCase(getClass().getSimpleName());
    }

    /** Converts a PascalCase type name to a kebab-case topic name. */
    static String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isBlank()) {
            throw new IllegalArgumentException("event class name must not be blank");
        }
        StringBuilder out = new StringBuilder(pascalCase.length() + 8);
        for (int i = 0; i < pascalCase.length(); i++) {
            char c = pascalCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl common-events test`

Expected: PASS, 5 tests.

- [ ] **Step 7: Verify the whole reactor builds**

Run: `./mvnw clean test`

Expected: `BUILD SUCCESS`. If the enforcer fails here, `JAVA_HOME` is not JDK 21 — return to Task 1 Step 2.

- [ ] **Step 8: Commit**

```bash
git add pom.xml common-events
git commit -m "feat: add root aggregator and common-events published language module"
```

---

### Task 3: `event-catalog-service` bootstrap

Creates the module, its Spring Boot entry point and its H2 configuration, and proves the application context loads. No domain logic yet.

**Files:**
- Create: `event-catalog-service/pom.xml`
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplication.java`
- Create: `event-catalog-service/src/main/resources/application.yml`
- Modify: `pom.xml` — add the module
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplicationTest.java`

**Interfaces:**
- Consumes: `au.edu.uow.csci318:common-events` from Task 2
- Produces: `au.edu.uow.csci318:event-catalog-service:0.1.0-SNAPSHOT`, base package `au.edu.uow.csci318.conference.catalog`, running on port 8081

- [ ] **Step 1: Register the module in the root POM**

In `pom.xml`, replace the `<modules>` block with:

```xml
  <modules>
    <module>common-events</module>
    <module>event-catalog-service</module>
  </modules>
```

- [ ] **Step 2: Write the service POM**

Create `event-catalog-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>au.edu.uow.csci318</groupId>
    <artifactId>conference-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>event-catalog-service</artifactId>
  <name>ConfHub Event Catalog Service</name>
  <description>Programme bounded context: events, sessions, speakers, venues</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>au.edu.uow.csci318</groupId>
      <artifactId>common-events</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write the application configuration**

Create `event-catalog-service/src/main/resources/application.yml`:

```yaml
server:
  port: 8081

spring:
  application:
    name: event-catalog-service
  datasource:
    url: jdbc:h2:mem:catalogdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
  h2:
    console:
      enabled: true
      path: /h2-console

logging:
  level:
    au.edu.uow.csci318.conference: DEBUG
```

- [ ] **Step 4: Write the failing test**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplicationTest.java`:

```java
package au.edu.uow.csci318.conference.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EventCatalogApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getApplicationName()).isNotNull();
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: failure — no `@SpringBootConfiguration` found.

- [ ] **Step 6: Write the application entry point**

Create `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplication.java`:

```java
package au.edu.uow.csci318.conference.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Programme bounded context.
 *
 * <p>Owns events, sessions, speakers and venues, and is the only service permitted to
 * write to the {@code catalogdb} schema.
 */
@SpringBootApplication
public class EventCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventCatalogApplication.class, args);
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add pom.xml event-catalog-service
git commit -m "feat: bootstrap event-catalog-service module"
```

---

### Task 4: `Event` aggregate and value objects

Pure domain layer, tested with plain JUnit and no Spring context. This is the task that demonstrates DDD tactical patterns for report section 3.3, so the invariants matter more than the getters.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/EventStatus.java`
- Create: `.../domain/model/Venue.java`
- Create: `.../domain/model/DateRange.java`
- Create: `.../domain/model/Event.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventTest.java`
- Test: `.../domain/model/VenueTest.java`
- Test: `.../domain/model/DateRangeTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces:
  - `enum EventStatus { DRAFT, PUBLISHED, CANCELLED }`
  - `record Venue(String name, String addressLine, String city)`
  - `record DateRange(LocalDate start, LocalDate end)` with `boolean contains(LocalDate)`
  - `class Event` with `static Event createDraft(String id, String name, String description, Venue venue, DateRange dateRange)` and getters `getId()`, `getName()`, `getDescription()`, `getStatus()`, `getVenue()`, `getDateRange()`

- [ ] **Step 1: Write the failing value-object tests**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/DateRangeTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 14);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 9, 16);

    @Test
    void acceptsAMultiDayRange() {
        DateRange range = new DateRange(DAY_1, DAY_3);

        assertThat(range.start()).isEqualTo(DAY_1);
        assertThat(range.end()).isEqualTo(DAY_3);
    }

    @Test
    void acceptsASingleDayRange() {
        DateRange range = new DateRange(DAY_1, DAY_1);

        assertThat(range.contains(DAY_1)).isTrue();
    }

    @Test
    void rejectsAnEndBeforeItsStart() {
        assertThatThrownBy(() -> new DateRange(DAY_3, DAY_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede");
    }

    @Test
    void rejectsMissingBounds() {
        assertThatThrownBy(() -> new DateRange(null, DAY_3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DateRange(DAY_1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsIsInclusiveOfBothBounds() {
        DateRange range = new DateRange(DAY_1, DAY_3);

        assertThat(range.contains(DAY_1)).isTrue();
        assertThat(range.contains(DAY_3)).isTrue();
        assertThat(range.contains(LocalDate.of(2026, 9, 15))).isTrue();
        assertThat(range.contains(DAY_1.minusDays(1))).isFalse();
        assertThat(range.contains(DAY_3.plusDays(1))).isFalse();
    }
}
```

Create `.../domain/model/VenueTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueTest {

    @Test
    void acceptsACompleteVenue() {
        Venue venue = new Venue("Innovation Campus", "Squires Way", "North Wollongong");

        assertThat(venue.city()).isEqualTo("North Wollongong");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Venue("  ", "Squires Way", "North Wollongong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue name");
    }

    @Test
    void rejectsABlankAddressLine() {
        assertThatThrownBy(() -> new Venue("Innovation Campus", "", "North Wollongong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue address line");
    }

    @Test
    void rejectsABlankCity() {
        assertThatThrownBy(() -> new Venue("Innovation Campus", "Squires Way", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue city");
    }

    @Test
    void isComparedByValueNotIdentity() {
        Venue a = new Venue("Innovation Campus", "Squires Way", "North Wollongong");
        Venue b = new Venue("Innovation Campus", "Squires Way", "North Wollongong");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: compilation failure — `cannot find symbol: class DateRange`.

- [ ] **Step 3: Write the value objects**

Create `.../domain/model/EventStatus.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

/** Lifecycle of an {@link Event}. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
```

Create `.../domain/model/Venue.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

/**
 * Value object describing where an event is held.
 *
 * <p>Has no identity: two venues with the same name, address and city are the same venue.
 */
public record Venue(String name, String addressLine, String city) {

    public Venue {
        requireText(name, "venue name");
        requireText(addressLine, "venue address line");
        requireText(city, "venue city");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

Create `.../domain/model/DateRange.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import java.time.LocalDate;

/**
 * Value object for an inclusive span of days.
 *
 * <p>Used for the dates an event runs over. Sessions added in M1 must fall within it.
 */
public record DateRange(LocalDate start, LocalDate end) {

    public DateRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("date range requires both a start and an end");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("date range end must not precede its start");
        }
    }

    /** True when {@code day} falls within this range, inclusive of both bounds. */
    public boolean contains(LocalDate day) {
        return day != null && !day.isBefore(start) && !day.isAfter(end);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: PASS, 9 value-object tests plus the context-loads test.

- [ ] **Step 5: Write the failing aggregate test**

Create `.../domain/model/EventTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

    @Test
    void newEventsStartAsDrafts() {
        Event event = Event.createDraft("evt-1", "SoftEng Summit", "Three days of practice", VENUE, DATES);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getId()).isEqualTo("evt-1");
        assertThat(event.getName()).isEqualTo("SoftEng Summit");
        assertThat(event.getVenue()).isEqualTo(VENUE);
        assertThat(event.getDateRange()).isEqualTo(DATES);
    }

    @Test
    void allowsAnAbsentDescription() {
        Event event = Event.createDraft("evt-2", "SoftEng Summit", null, VENUE, DATES);

        assertThat(event.getDescription()).isNull();
    }

    @Test
    void rejectsABlankId() {
        assertThatThrownBy(() -> Event.createDraft("  ", "SoftEng Summit", null, VENUE, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Event.createDraft("evt-3", "", null, VENUE, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event name");
    }

    @Test
    void rejectsAMissingVenue() {
        assertThatThrownBy(() -> Event.createDraft("evt-4", "SoftEng Summit", null, null, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue");
    }

    @Test
    void rejectsAMissingDateRange() {
        assertThatThrownBy(() -> Event.createDraft("evt-5", "SoftEng Summit", null, VENUE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date range");
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: compilation failure — `cannot find symbol: class Event`.

- [ ] **Step 7: Write the `Event` aggregate root**

Create `.../domain/model/Event.java`. It is written without JPA annotations here; Task 5 adds them.

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

/**
 * Aggregate root for the Programme bounded context.
 *
 * <p>An event is created as a {@link EventStatus#DRAFT}. Sessions are added to the draft
 * (M1) and the event is then published, which opens it for registration. All changes to a
 * programme go through this root so that scheduling invariants can be enforced in one place.
 *
 * <p>Constructed only via {@link #createDraft}; the protected no-argument constructor exists
 * solely for JPA.
 */
public class Event {

    private String id;
    private String name;
    private String description;
    private EventStatus status;
    private Venue venue;
    private DateRange dateRange;

    protected Event() {
        // required by JPA
    }

    private Event(String id, String name, String description, Venue venue, DateRange dateRange) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.venue = venue;
        this.dateRange = dateRange;
        this.status = EventStatus.DRAFT;
    }

    /**
     * Creates a new draft event.
     *
     * @throws IllegalArgumentException if any required attribute is missing or blank
     */
    public static Event createDraft(String id,
                                    String name,
                                    String description,
                                    Venue venue,
                                    DateRange dateRange) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        if (venue == null) {
            throw new IllegalArgumentException("event venue must be supplied");
        }
        if (dateRange == null) {
            throw new IllegalArgumentException("event date range must be supplied");
        }
        return new Event(id, name, description, venue, dateRange);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Venue getVenue() {
        return venue;
    }

    public DateRange getDateRange() {
        return dateRange;
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: PASS, all tests.

- [ ] **Step 9: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add Event aggregate with Venue and DateRange value objects"
```

---

### Task 5: Persistence — JPA mapping and the repository port

Adds the JPA mapping and the port/adapter pair. Two mapping hazards are handled explicitly: `Venue.name` collides with `Event.name`, and `DateRange.end` is a reserved SQL word. Both need `@AttributeOverride`.

**Files:**
- Modify: `.../domain/model/Event.java` — add JPA annotations
- Modify: `.../domain/model/Venue.java` — add `@Embeddable`
- Modify: `.../domain/model/DateRange.java` — add `@Embeddable`
- Create: `.../domain/repository/EventRepository.java`
- Create: `.../infrastructure/persistence/EventJpaRepository.java`
- Create: `.../infrastructure/persistence/JpaEventRepository.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/infrastructure/persistence/JpaEventRepositoryTest.java`

**Interfaces:**
- Consumes: `Event`, `Venue`, `DateRange`, `EventStatus` from Task 4
- Produces: `interface EventRepository { Event save(Event event); Optional<Event> findById(String id); }`

- [ ] **Step 1: Write the failing persistence test**

Create `.../infrastructure/persistence/JpaEventRepositoryTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaEventRepository.class)
class JpaEventRepositoryTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

    @Autowired
    private EventRepository events;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Forces the pending insert to SQL and empties the persistence context, so the
     * subsequent read is a real SELECT rather than a hit on Hibernate's first-level cache.
     * Without this, the test would pass even if the column mappings were wrong.
     */
    private void flushAndDetachAll() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAnEventIncludingItsValueObjects() {
        events.save(Event.createDraft("evt-1", "SoftEng Summit", "Three days", VENUE, DATES));
        flushAndDetachAll();

        Optional<Event> found = events.findById("evt-1");

        assertThat(found).isPresent();
        Event event = found.orElseThrow();
        assertThat(event.getName()).isEqualTo("SoftEng Summit");
        assertThat(event.getDescription()).isEqualTo("Three days");
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getVenue()).isEqualTo(VENUE);
        assertThat(event.getDateRange()).isEqualTo(DATES);
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(events.findById("does-not-exist")).isEmpty();
    }

    @Test
    void persistsAnEventWithoutADescription() {
        events.save(Event.createDraft("evt-2", "SoftEng Summit", null, VENUE, DATES));
        flushAndDetachAll();

        assertThat(events.findById("evt-2").orElseThrow().getDescription()).isNull();
    }

    @Test
    void storesTheVenueAndDateRangeInTheirRenamedColumns() {
        events.save(Event.createDraft("evt-3", "SoftEng Summit", null, VENUE, DATES));
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT venue_name, venue_city, starts_on, ends_on FROM events WHERE id = 'evt-3'")
                .getSingleResult();

        assertThat(row[0]).isEqualTo("Innovation Campus");
        assertThat(row[1]).isEqualTo("North Wollongong");
        assertThat(row[2].toString()).isEqualTo("2026-09-14");
        assertThat(row[3].toString()).isEqualTo("2026-09-16");
    }
}
```

The last test asserts against the physical column names directly. It is the only thing that
catches an `@AttributeOverride` being dropped in a later refactor — a failure that would
otherwise show up as an obscure Hibernate error in M1.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: compilation failure — `cannot find symbol: class EventRepository`.

- [ ] **Step 3: Add JPA annotations to the value objects**

Replace the class declaration in `.../domain/model/Venue.java` so the file reads:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object describing where an event is held.
 *
 * <p>Has no identity: two venues with the same name, address and city are the same venue.
 */
@Embeddable
public record Venue(String name, String addressLine, String city) {

    public Venue {
        requireText(name, "venue name");
        requireText(addressLine, "venue address line");
        requireText(city, "venue city");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

Do the same for `.../domain/model/DateRange.java`, adding `import jakarta.persistence.Embeddable;` and `@Embeddable` above `public record DateRange`.

> **If Hibernate rejects the record embeddables** with an error mentioning a missing default constructor, convert `Venue` and `DateRange` to plain final classes with a `protected` no-argument constructor, private final-less fields, accessor methods named `name()`, `addressLine()`, `city()` / `start()`, `end()`, and hand-written `equals`/`hashCode`. Keep the accessor names identical so no other file changes. Record embeddables are supported in the Hibernate 6.6 that Spring Boot 3.5.15 ships, so this fallback should not be needed — but verify at this step rather than discovering it later.

- [ ] **Step 4: Add JPA annotations to the `Event` aggregate**

In `.../domain/model/Event.java`, add these imports:

```java
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
```

Replace the class declaration and field block with:

```java
@Entity
@Table(name = "events")
public class Event {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EventStatus status;

    // Venue.name would collide with Event.name, so every attribute is overridden.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "venue_name", nullable = false)),
            @AttributeOverride(name = "addressLine", column = @Column(name = "venue_address_line", nullable = false)),
            @AttributeOverride(name = "city", column = @Column(name = "venue_city", nullable = false))
    })
    private Venue venue;

    // "start" and "end" are reserved SQL words, so both are renamed.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_on", nullable = false)),
            @AttributeOverride(name = "end", column = @Column(name = "ends_on", nullable = false))
    })
    private DateRange dateRange;
```

Leave the constructors, factory method and getters exactly as they are.

- [ ] **Step 5: Write the repository port**

Create `.../domain/repository/EventRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.repository;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;

import java.util.Optional;

/**
 * Persistence port for the {@link Event} aggregate.
 *
 * <p>Declared in the domain layer and implemented in the infrastructure layer, so that the
 * dependency points inward: the domain defines what it needs, infrastructure supplies it.
 */
public interface EventRepository {

    Event save(Event event);

    Optional<Event> findById(String id);
}
```

- [ ] **Step 6: Write the persistence adapter**

Create `.../infrastructure/persistence/EventJpaRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code events} table. Package-private: the domain sees only the port. */
interface EventJpaRepository extends JpaRepository<Event, String> {
}
```

Create `.../infrastructure/persistence/JpaEventRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link EventRepository} port. */
@Repository
public class JpaEventRepository implements EventRepository {

    private final EventJpaRepository delegate;

    public JpaEventRepository(EventJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Event save(Event event) {
        return delegate.save(event);
    }

    @Override
    public Optional<Event> findById(String id) {
        return delegate.findById(id);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: PASS. If a test fails with `Table "EVENTS" not found` or a column-name error, re-check the `@AttributeOverrides` in Step 4.

- [ ] **Step 8: Commit**

```bash
git add event-catalog-service
git commit -m "feat: persist Event aggregate via repository port and JPA adapter"
```

---

### Task 6: REST API for FR-01 — create and read a draft event

Completes the vertical slice through all four layers, with the error mapping every later service copies.

**Files:**
- Create: `.../domain/EventNotFoundException.java`
- Create: `.../application/command/CreateEventCommand.java`
- Create: `.../application/EventApplicationService.java`
- Create: `.../api/dto/CreateEventRequest.java`
- Create: `.../api/dto/EventResponse.java`
- Create: `.../api/EventController.java`
- Create: `.../api/GlobalExceptionHandler.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/EventControllerTest.java`

**Interfaces:**
- Consumes: `EventRepository`, `Event`, `Venue`, `DateRange` from Tasks 4 and 5
- Produces:
  - `POST /api/v1/events` → `201 Created` with `EventResponse`
  - `GET /api/v1/events/{id}` → `200 OK` with `EventResponse`, `404` with `ApiError`
  - `record ApiError(String code, String message)`

- [ ] **Step 1: Write the failing controller test**

Create `.../api/EventControllerTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class EventControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static String validRequestJson() {
        return """
                {
                  "name": "SoftEng Summit",
                  "description": "Three days of practice",
                  "venue": {
                    "name": "Innovation Campus",
                    "addressLine": "Squires Way",
                    "city": "North Wollongong"
                  },
                  "startDate": "2026-09-14",
                  "endDate": "2026-09-16"
                }
                """;
    }

    @Test
    void createsADraftEvent() throws Exception {
        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("SoftEng Summit"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.venue.city").value("North Wollongong"))
                .andExpect(jsonPath("$.startDate").value("2026-09-14"))
                .andExpect(jsonPath("$.endDate").value("2026-09-16"));
    }

    @Test
    void readsBackACreatedEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc().perform(get("/api/v1/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("SoftEng Summit"));
    }

    @Test
    void rejectsABlankName() throws Exception {
        String invalid = validRequestJson().replace("\"SoftEng Summit\"", "\"   \"");

        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    @Test
    void rejectsAnEndDateBeforeItsStartDate() throws Exception {
        String invalid = validRequestJson().replace("\"2026-09-16\"", "\"2026-09-01\"");

        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForAnUnknownEvent() throws Exception {
        mockMvc().perform(get("/api/v1/events/{id}", "no-such-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: failures — every request returns 404 because no controller is mapped.

- [ ] **Step 3: Write the domain exception**

Create `.../domain/EventNotFoundException.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when an event id does not resolve to a known event. */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String id) {
        super("no event with id " + id);
    }
}
```

- [ ] **Step 4: Write the application layer**

Create `.../application/command/CreateEventCommand.java`:

```java
package au.edu.uow.csci318.conference.catalog.application.command;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;

/**
 * Presentation-independent input for creating a draft event.
 *
 * <p>Keeping this separate from the HTTP DTO means the application layer never depends on
 * the wire format, which is what allows the same use case to be driven from a test, a CLI
 * or an agent tool without change.
 */
public record CreateEventCommand(String name, String description, Venue venue, DateRange dateRange) {
}
```

Create `.../application/EventApplicationService.java`:

```java
package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Orchestrates event use cases and owns the transaction boundary. */
@Service
public class EventApplicationService {

    private final EventRepository events;

    public EventApplicationService(EventRepository events) {
        this.events = events;
    }

    @Transactional
    public Event createDraft(CreateEventCommand command) {
        Event event = Event.createDraft(
                UUID.randomUUID().toString(),
                command.name(),
                command.description(),
                command.venue(),
                command.dateRange());
        return events.save(event);
    }

    @Transactional(readOnly = true)
    public Event getById(String id) {
        return events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }
}
```

- [ ] **Step 5: Write the DTOs**

Create `.../api/dto/CreateEventRequest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Wire contract for {@code POST /api/v1/events}. */
public record CreateEventRequest(
        @NotBlank(message = "must not be blank") String name,
        String description,
        @NotNull(message = "must be supplied") @Valid VenuePayload venue,
        @NotNull(message = "must be supplied") LocalDate startDate,
        @NotNull(message = "must be supplied") LocalDate endDate) {

    public record VenuePayload(
            @NotBlank(message = "must not be blank") String name,
            @NotBlank(message = "must not be blank") String addressLine,
            @NotBlank(message = "must not be blank") String city) {
    }

    /** Translates the wire contract into the application layer's command. */
    public CreateEventCommand toCommand() {
        return new CreateEventCommand(
                name,
                description,
                new Venue(venue.name(), venue.addressLine(), venue.city()),
                new DateRange(startDate, endDate));
    }
}
```

Create `.../api/dto/EventResponse.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;

import java.time.LocalDate;

/** Wire contract returned for a single event. */
public record EventResponse(
        String id,
        String name,
        String description,
        String status,
        VenuePayload venue,
        LocalDate startDate,
        LocalDate endDate) {

    public record VenuePayload(String name, String addressLine, String city) {
    }

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getStatus().name(),
                new VenuePayload(
                        event.getVenue().name(),
                        event.getVenue().addressLine(),
                        event.getVenue().city()),
                event.getDateRange().start(),
                event.getDateRange().end());
    }
}
```

- [ ] **Step 6: Write the controller and exception handler**

Create `.../api/EventController.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.CreateEventRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.EventResponse;
import au.edu.uow.csci318.conference.catalog.application.EventApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for the event programme. Implements FR-01 and read-by-id. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventApplicationService events;

    public EventController(EventApplicationService events) {
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createDraft(@Valid @RequestBody CreateEventRequest request) {
        EventResponse body = EventResponse.from(events.createDraft(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable String id) {
        return EventResponse.from(events.getById(id));
    }
}
```

Create `.../api/GlobalExceptionHandler.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Maps domain and validation failures onto the HTTP error contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EventNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("EVENT_NOT_FOUND", ex.getMessage()));
    }

    /**
     * Domain invariant violations surface as IllegalArgumentException from value object
     * constructors and aggregate factories, and are a client error, not a server error.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", detail));
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl event-catalog-service -am test`

Expected: PASS, all five controller tests plus the earlier ones.

- [ ] **Step 8: Verify the service runs for real**

`spring-boot:run` resolves dependencies from the local Maven repository rather than the
reactor, so `common-events` must be installed first. `-am` does **not** help here — it would
try to run the `spring-boot:run` goal against `common-events` too, which has no main class.

```bash
./mvnw clean install -DskipTests
./mvnw spring-boot:run -pl event-catalog-service
```

In a second terminal:

```bash
curl -s -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}'
```

Expected: HTTP 201 with a JSON body containing a generated `id` and `"status":"DRAFT"`. Stop the service with Ctrl+C.

- [ ] **Step 9: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add REST API for creating and reading draft events (FR-01)"
```

---

### Task 7: Skeletons for the remaining five services

Each is a runnable Spring Boot application with the four layer packages in place, so M1 onward has somewhere to put code. Deliberately minimal: messaging, JPA and LangChain4j dependencies are added in the milestone that first needs them.

**Files:**
- Create: `registration-service/`, `payment-service/`, `ticketing-service/`, `analytics-service/`, `concierge-service/` — each with `pom.xml`, an application class, `application.yml`, a context test, and empty layer packages
- Modify: `pom.xml` — add all five modules

**Interfaces:**
- Consumes: the root POM from Task 2
- Produces: artifacts `registration-service`, `payment-service`, `ticketing-service`, `analytics-service`, `concierge-service`, base packages `au.edu.uow.csci318.conference.{registration,payment,ticketing,analytics,concierge}`

- [ ] **Step 1: Register all five modules in the root POM**

In `pom.xml`, replace the `<modules>` block with:

```xml
  <modules>
    <module>common-events</module>
    <module>event-catalog-service</module>
    <module>registration-service</module>
    <module>payment-service</module>
    <module>ticketing-service</module>
    <module>analytics-service</module>
    <module>concierge-service</module>
  </modules>
```

- [ ] **Step 2: Create the three persistent services**

For each of the following, create `<module>/pom.xml` by copying `event-catalog-service/pom.xml` verbatim and changing only the `<artifactId>`, `<name>` and `<description>`:

| Module | artifactId | name | description |
| :--- | :--- | :--- | :--- |
| `registration-service` | `registration-service` | `ConfHub Registration Service` | `Registration bounded context: registrations, seat holds, waitlists` |
| `payment-service` | `payment-service` | `ConfHub Payment Service` | `Billing bounded context: payment orders, refunds, refund policy` |
| `ticketing-service` | `ticketing-service` | `ConfHub Ticketing Service` | `Access bounded context: tickets, transfers, gate check-in` |

- [ ] **Step 3: Create the two stateless services**

For `analytics-service` and `concierge-service`, create `<module>/pom.xml` the same way but **remove** the `spring-boot-starter-data-jpa` and `com.h2database:h2` dependencies — neither service owns a database, per spec §4.2. Keep `spring-boot-starter-web`, `spring-boot-starter-validation`, `common-events` and `spring-boot-starter-test`.

| Module | artifactId | name | description |
| :--- | :--- | :--- | :--- |
| `analytics-service` | `analytics-service` | `ConfHub Analytics Service` | `Operational insight context: Kafka Streams read models. Owns no database.` |
| `concierge-service` | `concierge-service` | `ConfHub Concierge Service` | `Attendee assistance context: LangChain4j agents. Owns no database.` |

- [ ] **Step 4: Write the application classes**

For each module, create `<module>/src/main/java/au/edu/uow/csci318/conference/<pkg>/<Class>.java` using this template, substituting from the table below:

```java
package au.edu.uow.csci318.conference.PKG;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the CONTEXT bounded context. */
@SpringBootApplication
public class CLASS {

    public static void main(String[] args) {
        SpringApplication.run(CLASS.class, args);
    }
}
```

| Module | PKG | CLASS | CONTEXT |
| :--- | :--- | :--- | :--- |
| `registration-service` | `registration` | `RegistrationApplication` | Registration |
| `payment-service` | `payment` | `PaymentApplication` | Billing |
| `ticketing-service` | `ticketing` | `TicketingApplication` | Access |
| `analytics-service` | `analytics` | `AnalyticsApplication` | Operational insight |
| `concierge-service` | `concierge` | `ConciergeApplication` | Attendee assistance |

- [ ] **Step 5: Write the configuration for the three persistent services**

For `registration-service` (port 8082, schema `regdb`), `payment-service` (8083, `paydb`) and `ticketing-service` (8084, `ticketdb`), create `<module>/src/main/resources/application.yml`:

```yaml
server:
  port: PORT

spring:
  application:
    name: MODULE
  datasource:
    url: jdbc:h2:mem:SCHEMA;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
  h2:
    console:
      enabled: true
      path: /h2-console

logging:
  level:
    au.edu.uow.csci318.conference: DEBUG
```

- [ ] **Step 6: Write the configuration for the two stateless services**

For `analytics-service` (8085) and `concierge-service` (8086), create `<module>/src/main/resources/application.yml`:

```yaml
server:
  port: PORT

spring:
  application:
    name: MODULE

logging:
  level:
    au.edu.uow.csci318.conference: DEBUG
```

- [ ] **Step 7: Write a context test for each module**

For each module create `<module>/src/test/java/au/edu/uow/csci318/conference/<pkg>/<Class>Test.java`:

```java
package au.edu.uow.csci318.conference.PKG;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CLASSTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
    }
}
```

- [ ] **Step 8: Create the empty layer packages**

So that later milestones drop files into a structure that already matches spec §9, create a `.gitkeep` in each layer directory of all five modules:

```bash
for m in registration:registration payment:payment ticketing:ticketing analytics:analytics concierge:concierge; do
  mod="${m%%:*}-service"; pkg="${m##*:}"
  for layer in api application domain infrastructure; do
    mkdir -p "$mod/src/main/java/au/edu/uow/csci318/conference/$pkg/$layer"
    touch "$mod/src/main/java/au/edu/uow/csci318/conference/$pkg/$layer/.gitkeep"
  done
done
```

- [ ] **Step 9: Run the full build**

Run: `./mvnw clean test`

Expected: `BUILD SUCCESS`, `Reactor Summary` listing all seven modules.

- [ ] **Step 10: Commit**

```bash
git add pom.xml registration-service payment-service ticketing-service analytics-service concierge-service
git commit -m "feat: add skeletons for registration, payment, ticketing, analytics and concierge services"
```

---

### Task 8: Kafka via Docker Compose

Kafka runs in KRaft mode with no ZooKeeper. Two listeners are configured: one advertised as `localhost:9092` for the services running on the host, one as `kafka:29092` for containers on the Compose network. Getting this wrong is the most common cause of "connection refused" later, so it is set up and verified now rather than in M1.

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing
- Produces: a Kafka broker at `localhost:9092` and Kafka UI at `http://localhost:8080`

- [ ] **Step 1: Write the Compose file**

Create `docker-compose.yml`:

```yaml
name: confhub

services:
  kafka:
    image: apache/kafka:4.3.1
    container_name: confhub-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      CLUSTER_ID: confhub-local-cluster-01
      # HOST is for services running on the developer's machine.
      # DOCKER is for containers on this Compose network, such as kafka-ui.
      KAFKA_LISTENERS: HOST://0.0.0.0:9092,DOCKER://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: HOST://localhost:9092,DOCKER://kafka:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: HOST:PLAINTEXT,DOCKER:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: DOCKER
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
      interval: 10s
      timeout: 10s
      retries: 12

  kafka-ui:
    image: provectuslabs/kafka-ui:v0.7.2
    container_name: confhub-kafka-ui
    ports:
      - "8080:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: confhub-local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
      DYNAMIC_CONFIG_ENABLED: "true"
    depends_on:
      kafka:
        condition: service_healthy
```

- [ ] **Step 2: Start the stack**

Run: `docker compose up -d`

Expected: both containers start. Check with `docker compose ps` — `confhub-kafka` should reach `healthy`, which can take up to 30 seconds.

- [ ] **Step 3: Verify the broker accepts a topic from the host**

```bash
docker exec confhub-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic smoke-test --partitions 1 --replication-factor 1
docker exec confhub-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

Expected: `smoke-test` appears in the list.

- [ ] **Step 4: Verify Kafka UI reaches the broker**

Open `http://localhost:8080`. Expected: the `confhub-local` cluster shows as online and `smoke-test` appears under Topics. If the cluster shows offline, the `DOCKER` listener is misconfigured — recheck `KAFKA_ADVERTISED_LISTENERS`.

- [ ] **Step 5: Clean up the smoke-test topic**

```bash
docker exec confhub-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --delete --topic smoke-test
```

- [ ] **Step 6: Confirm the build still passes with Kafka running and with it stopped**

```bash
./mvnw clean test
docker compose down
./mvnw clean test
```

Expected: `BUILD SUCCESS` both times. No service depends on Kafka yet, and the global constraint requires the suite to pass without Docker.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: add Kafka and Kafka UI via Docker Compose"
```

---

### Task 9: Specification files mirroring the university reference repository

The marker knows the `gxshub/book-and-library-management-system-v1` layout. Matching it makes the submission legible. Content is derived from the approved design document — this task transcribes and reformats, it does not invent.

**Files:**
- Create: `specs/1-technical-architecture.md`
- Create: `specs/2-user-stories.md`
- Create: `specs/3-domain-models.md`
- Create: `specs/4-api-endpoints.md`
- Create: `specs/5-events-and-streams.md`
- Create: `specs/6-agentic-ai.md`
- Create: `specs/openapi-contract.yaml`
- Create: `agent-prompts/dev-agent.md`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md`
- Produces: the specification set every later milestone's contract tests are generated from

- [ ] **Step 1: Write `specs/1-technical-architecture.md`**

Transcribe from design §4 (services and data ownership), §6 (event-driven architecture), §9 (layered architecture) and §10 (technology and version matrix). Include the service table with ports and owned schemas, the context map diagram, the four-layer package structure, and the version matrix. Replace the reference repository's `## Design Principles` / `## Future Considerations` "Todo" stubs with real content drawn from design §4.2 (data ownership, why two services own no database) and §16 (out of scope).

- [ ] **Step 2: Write `specs/2-user-stories.md`**

Transcribe design §3. Use the reference repository's format: an overview table of `Service | User Story ID | User Story Name | Role(s)`, then a section per story written as "**As a** … **I want to** …". Use the FR-01 … FR-18 identifiers from the design so requirements stay traceable into the report. Mark FR-14, FR-15 and FR-16 as real-time analysis stories and FR-17 and FR-18 as agentic AI stories, since the marking rubric looks for them explicitly.

- [ ] **Step 3: Write `specs/3-domain-models.md`**

Transcribe design §5. Keep the reference repository's per-field table format (`Field Name | Type | Description | Constraints / Notes`) rather than the design's condensed tables, and keep the `[AR]` / `[E]` / `[VO]` / `[DS]` / `[RM]` annotations — they are what report section 3.3 argues from. Include the "Domain Classes and Related User Stories" mapping table at the top, as the reference does.

- [ ] **Step 4: Write `specs/4-api-endpoints.md`**

One table per service in the reference repository's format: `Feature | Method | Endpoint Path | Request Body | Response (Success) | Description`. Cover all six services using the endpoint list in design §12 and §8. Mark endpoints not yet implemented as of M0 with `(M1)`, `(M2)` and so on, so the file doubles as a progress tracker.

For M0, only these exist and must be listed without a milestone marker:

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| FR-01 | `POST` | `/api/v1/events` | `CreateEventRequest` | `201 Created` (`EventResponse`) | Create a draft event |
| — | `GET` | `/api/v1/events/{id}` | *None* | `200 OK` (`EventResponse`) | Get an event by id |

- [ ] **Step 5: Write `specs/5-events-and-streams.md`**

Transcribe design §6.1 (the full topic table with keys, producers and consumers), §6.3 (the saga flows including both compensation paths) and §7 (both stream topologies with their ksqlDB equivalents). This file has no counterpart in the reference repository — it exists because the CSCI318 specification requires event-driven architecture and stream processing that the reference project did not have.

- [ ] **Step 6: Write `specs/6-agentic-ai.md`**

Transcribe design §8: both agents, their tool tables, the guardrail configuration, the model configuration, and the named agentic patterns. State explicitly that `detectTimeClashes` and the refund threshold are enforced in code rather than in prompts, and why — that argument is the core of report section 3.6.

- [ ] **Step 7: Write `specs/openapi-contract.yaml`**

Create an OpenAPI 3.0.3 document. For M0 it covers only the two implemented endpoints; later milestones extend it, and contract tests are generated from it.

```yaml
openapi: 3.0.3
info:
  title: ConfHub Event Catalog Service
  description: Programme bounded context. CSCI318 Spring 2026 group project.
  version: 0.1.0
servers:
  - url: http://localhost:8081
    description: Local development
paths:
  /api/v1/events:
    post:
      summary: Create a draft event
      operationId: createDraftEvent
      tags: [Events]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateEventRequest'
      responses:
        '201':
          description: Draft event created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventResponse'
        '400':
          description: Validation failure or domain invariant violation
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
  /api/v1/events/{id}:
    get:
      summary: Get an event by id
      operationId: getEventById
      tags: [Events]
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        '200':
          description: The event
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventResponse'
        '404':
          description: No event with that id
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
components:
  schemas:
    Venue:
      type: object
      required: [name, addressLine, city]
      properties:
        name:
          type: string
          example: Innovation Campus
        addressLine:
          type: string
          example: Squires Way
        city:
          type: string
          example: North Wollongong
    CreateEventRequest:
      type: object
      required: [name, venue, startDate, endDate]
      properties:
        name:
          type: string
          example: SoftEng Summit
        description:
          type: string
          nullable: true
          example: Three days of practice
        venue:
          $ref: '#/components/schemas/Venue'
        startDate:
          type: string
          format: date
          example: '2026-09-14'
        endDate:
          type: string
          format: date
          example: '2026-09-16'
    EventResponse:
      type: object
      required: [id, name, status, venue, startDate, endDate]
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        description:
          type: string
          nullable: true
        status:
          type: string
          enum: [DRAFT, PUBLISHED, CANCELLED]
        venue:
          $ref: '#/components/schemas/Venue'
        startDate:
          type: string
          format: date
        endDate:
          type: string
          format: date
    ApiError:
      type: object
      required: [code, message]
      properties:
        code:
          type: string
          enum: [EVENT_NOT_FOUND, INVALID_REQUEST, VALIDATION_FAILED]
        message:
          type: string
```

- [ ] **Step 8: Write `agent-prompts/dev-agent.md`**

Adapt the reference repository's four-phase development agent prompt to ConfHub:

```markdown
# Role & Persona: DEV AGENT (Senior Software Engineer)

You are an expert Senior Spring Boot Developer working on ConfHub, a conference and event
registration platform built for CSCI318 Spring 2026.

CRITICAL BOUNDARY: The `/specs/` directory is STRICTLY READ-ONLY. Never edit specification
files. If the specs are wrong, HALT and report — do not work around them.

CRITICAL BOUNDARY: The dependency allowlist in `/specs/1-technical-architecture.md` is
fixed by the subject coordinator. Never add a dependency outside it. If a task appears to
require one, HALT and report.

# Execution Pipeline

## Phase 1: Specification Audit
- Read all files in `/specs/`.
- If specifications contradict one another or leave a critical path undefined, HALT
  IMMEDIATELY, report the discrepancy, and request human clarification.

## Phase 2: Environment & Dependency Audit
- Verify `./mvnw -v` reports Maven 3.9.x on Java 21. If not, HALT.
- Inspect the root and module `pom.xml` files. Add modules and dependencies only from the
  allowlist, following the existing multi-module structure.
- Run `./mvnw test-compile` to verify the build setup.

## Phase 3: Contract-First Test Generation
- Use `/specs/openapi-contract.yaml` to generate `MockMvc` integration tests covering happy
  paths and error paths (400, 404, 409 where specified).
- Use `/specs/5-events-and-streams.md` to generate messaging tests with the Spring Cloud
  Stream test binder, and stream topology tests with `TopologyTestDriver`.
- Retain all tests from previous milestones to prevent regressions.

## Phase 4: Implementation & Verification Loop
- Implement strictly following the layered structure in `/specs/1-technical-architecture.md`:
  `api` / `application` / `domain` / `infrastructure`. The domain layer must not import
  Spring except for JPA mapping annotations on aggregate roots.
- Execute `./mvnw clean test`.
- Resolve compiler errors and failing assertions until all tests pass cleanly.
- `./mvnw clean test` must pass with no Docker container running.
```

- [ ] **Step 9: Verify every specification file is present and non-empty**

```bash
for f in specs/1-technical-architecture.md specs/2-user-stories.md specs/3-domain-models.md \
         specs/4-api-endpoints.md specs/5-events-and-streams.md specs/6-agentic-ai.md \
         specs/openapi-contract.yaml agent-prompts/dev-agent.md; do
  if [ -s "$f" ]; then echo "OK   $f ($(wc -l < "$f") lines)"; else echo "FAIL $f"; fi
done
```

Expected: eight `OK` lines, none `FAIL`.

- [ ] **Step 10: Commit**

```bash
git add specs agent-prompts
git commit -m "docs: add specification set and development agent prompt"
```

---

### Task 10: Assessment artefacts and README

The meeting minutes are worth 3 marks across Weeks 3–11, and report Section 5 requires referenced evidence of AI collaboration. Both are cheap to maintain weekly and painful to reconstruct at the end.

**Files:**
- Create: `docs/meetings/TEMPLATE.md`
- Create: `docs/meetings/README.md`
- Create: `docs/ai-collaboration-log.md`
- Create: `README.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–9
- Produces: the repository root README required by the CSCI318 specification

- [ ] **Step 1: Write the meeting minutes template**

Create `docs/meetings/TEMPLATE.md`:

```markdown
# Meeting Minutes — Week N

**Date:** YYYY-MM-DD
**Time:** HH:MM – HH:MM
**Location:** (room / online)

## Attendance

| Member | Present |
| :--- | :--- |
| Member A | Yes / No |
| Member B | Yes / No |
| Member C | Yes / No |

## Agenda

1.
2.
3.

## Progress since last meeting

| Member | Completed | In progress | Blocked by |
| :--- | :--- | :--- | :--- |
| Member A | | | |
| Member B | | | |
| Member C | | | |

## Discussion summary

## Decisions made

| Decision | Rationale |
| :--- | :--- |

## Action items for next week

| # | Action | Owner | Due |
| :--- | :--- | :--- | :--- |

## Next meeting

**Date:** YYYY-MM-DD · **Location:**
```

- [ ] **Step 2: Write the meetings README**

Create `docs/meetings/README.md`:

```markdown
# Weekly meeting minutes

Worth 3 marks. Minutes are submitted to Moodle every week from **Week 3 to Week 11
inclusive**, and each set must record the meeting date, attendance, progress reports,
discussion summary and action items — including what was done in the previous week and what
is planned for the next.

Copy `TEMPLATE.md` to `week-NN.md` at the start of each meeting and fill it in during the
meeting, not afterwards.

| Week | File | Submitted |
| :--- | :--- | :--- |
| 3 | `week-03.md` | ☐ |
| 4 | `week-04.md` | ☐ |
| 5 | `week-05.md` | ☐ |
| 6 | `week-06.md` | ☐ |
| 7 | `week-07.md` | ☐ |
| 8 | `week-08.md` | ☐ |
| 9 | `week-09.md` | ☐ |
| 10 | `week-10.md` | ☐ |
| 11 | `week-11.md` | ☐ |
```

- [ ] **Step 3: Write the AI collaboration log**

Create `docs/ai-collaboration-log.md`:

```markdown
# AI Collaboration Log

Evidence for report **Section 5 — AI-Driven Development Process**, which requires
representative examples of AI-generated proposals that the team **accepted, rejected or
modified**, with the rationale behind each decision.

Add an entry in the same sitting as the weekly minutes. Rejections and modifications are
worth more marks than acceptances, because they demonstrate engineering judgement rather
than delegation.

## Entry format

| Field | Content |
| :--- | :--- |
| **Date** | YYYY-MM-DD |
| **Member** | Who ran the tool |
| **Tool** | e.g. Claude Code, GitHub Copilot |
| **SDLC phase** | Requirements / domain modelling / architecture / implementation / testing / documentation |
| **What was asked** | Short description, or a link to the prompt artefact |
| **What was proposed** | Summary of the AI's output |
| **Decision** | Accepted / Modified / Rejected |
| **Rationale** | Why — this is the part the marker reads |
| **Artefact** | Path in this repository |

---

## 2026-08-22 — Architecture and design specification

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | (fill in) |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, domain modelling, architecture design |
| **What was asked** | Turn the group's feature list for a conference registration platform into a design meeting the CSCI318 architectural requirements |
| **What was proposed** | Six bounded contexts; orchestrated registration saga; Kafka Streams read models; two LangChain4j tool-calling agents |
| **Decision** | Modified |
| **Rationale** | The original feature list described the two AI features as natural-language requests, which would most likely have been built as single LLM calls. These were reformulated as tool-calling agents because the rubric grades *agentic design patterns*. A waitlist, a configurable hold window, a simulated gateway and demo scripts were added; Spring Boot 4.x was rejected because the LangChain4j Spring starter still targets Boot 3.5. |
| **Artefact** | `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` |
```

- [ ] **Step 4: Write the root README**

Create `README.md`:

````markdown
# ConfHub — Conference & Event Registration Platform

CSCI318 Software Engineering Practices & Principles, Spring 2026 — Group Project.

A registration and attendance platform for small-to-mid-sized conferences, built as six
Spring Boot microservices communicating over Apache Kafka, with Kafka Streams read models
for real-time operational insight and LangChain4j agents for attendee assistance.

## Architecture

| Service | Port | Bounded context | Owns |
| :--- | :--- | :--- | :--- |
| `event-catalog-service` | 8081 | Programme | `catalogdb` |
| `registration-service` | 8082 | Registration | `regdb` |
| `payment-service` | 8083 | Billing | `paydb` |
| `ticketing-service` | 8084 | Access | `ticketdb` |
| `analytics-service` | 8085 | Operational insight | no database — Kafka Streams state stores |
| `concierge-service` | 8086 | Attendee assistance | no database |

`common-events` holds the domain event records shared between contexts as a Published
Language.

Full specifications are in [`specs/`](specs/).

## Prerequisites

- **Eclipse Temurin JDK 21** — exactly 21. The build fails on any other version.
- **Docker Desktop** — for Kafka.
- **A Gemini API key** — free from <https://aistudio.google.com/apikey>. Needed only for the
  concierge service.

Maven is **not** required: the repository ships a wrapper.

## Configuration

```bash
cp .env.example .env
# then edit .env and set GEMINI_API_KEY
```

`.env` is gitignored and must never be committed.

## Running

Start Kafka and Kafka UI:

```bash
docker compose up -d
```

Kafka UI is then at <http://localhost:8080>.

Build and test everything:

```bash
./mvnw clean test          # macOS / Linux
.\mvnw.cmd clean test      # Windows PowerShell
```

The test suite passes without Docker running and without network access.

Run a service. The install step publishes `common-events` to your local Maven repository,
which `spring-boot:run` needs; you only have to repeat it when `common-events` changes.

```bash
./mvnw clean install -DskipTests
./mvnw spring-boot:run -pl event-catalog-service
```

Each service exposes an H2 console at `http://localhost:<port>/h2-console` (JDBC URL
`jdbc:h2:mem:<schema>`, user `sa`, empty password).

Stop Kafka:

```bash
docker compose down
```

## Sample inputs

### Create a draft event (FR-01)

```bash
curl -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SoftEng Summit",
    "description": "Three days of practice",
    "venue": {
      "name": "Innovation Campus",
      "addressLine": "Squires Way",
      "city": "North Wollongong"
    },
    "startDate": "2026-09-14",
    "endDate": "2026-09-16"
  }'
```

### Read an event back

```bash
curl http://localhost:8081/api/v1/events/{id}
```

## Repository layout

| Path | Contents |
| :--- | :--- |
| `specs/` | Architecture, user stories, domain models, API, events and streams, agentic AI, OpenAPI contract |
| `agent-prompts/` | Development agent prompt |
| `docs/meetings/` | Weekly meeting minutes, Weeks 3–11 |
| `docs/ai-collaboration-log.md` | Evidence for report Section 5 |
| `docs/superpowers/` | Design specification and implementation plans |
| `scripts/` | Seed data and demonstration scripts (from M4) |

## Project status

| Milestone | Weeks | Status |
| :--- | :--- | :--- |
| M0 — Skeleton | 3–4 | ✅ Complete |
| M1 — First event across a boundary | 5–6 | ☐ |
| M2 — The saga | 7–8 | ☐ |
| M3 — Streams | 9–10 | ☐ |
| M4 — Agents | 10–11 | ☐ |
| M5 — Submission | 11–12 | ☐ |
````

- [ ] **Step 5: Verify the README instructions actually work from a clean state**

```bash
./mvnw clean test
docker compose up -d
docker compose ps
docker compose down
```

Expected: `BUILD SUCCESS`, both containers healthy, clean shutdown. If any README command fails, fix the README rather than the reader.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/meetings docs/ai-collaboration-log.md
git commit -m "docs: add README, meeting minutes template and AI collaboration log"
```

---

## M0 Definition of Done

- [ ] `./mvnw -v` reports Maven 3.9.16 on Java 21
- [ ] `./mvnw clean test` succeeds with Docker stopped and all seven modules in the reactor summary
- [ ] `docker compose up -d` brings Kafka to healthy and Kafka UI shows the cluster online
- [ ] `POST /api/v1/events` returns 201 with a generated id and `DRAFT` status
- [ ] `GET /api/v1/events/{unknown}` returns 404 with `EVENT_NOT_FOUND`
- [ ] All eight files in `specs/` and `agent-prompts/` exist and are non-empty
- [ ] `README.md`, `docs/meetings/TEMPLATE.md` and `docs/ai-collaboration-log.md` exist
- [ ] Every layer package (`api`, `application`, `domain`, `infrastructure`) exists in all six services
- [ ] `.env` is gitignored and no secret is committed — verify with `git log -p | grep -i "api.key"` returning nothing meaningful

---

## Deferred to M1

Recorded here so nothing is lost between milestones:

- `Session`, `Speaker`, `Track`, `TimeSlot`, `SeatCapacity`, `Price` in the catalog domain
- `Event.publish()` and the "publishing requires at least one session" invariant (FR-03)
- Catalogue search by track, day, speaker and keyword (FR-05)
- The first real domain event records in `common-events`: `EventPublished`, `SessionAdded`, `SessionCapacityChanged`
- Spring Cloud Stream wiring: `StreamBridge` publisher in catalog, `Consumer` bean in registration
- `SessionView` read replica in `registration-service`
- Messaging tests using `spring-cloud-stream-test-binder`
