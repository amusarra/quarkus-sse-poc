# Quarkus Server-Sent Event (SSE) Proof of Concept (PoC)

This project is a Proof of Concept (PoC) that demonstrates how to handle long-running asynchronous tasks in a Quarkus application, using Server-Sent Events (SSE) to notify the client in real-time.

The implemented flow is as follows:

1. **Task Start**: The user, through a web interface, requests the generation of a document. This request starts an asynchronous process on the server.
2. **Immediate Response**: The server does not wait for the task to complete but responds immediately (e.g., with `202 Accepted`), freeing up the client.
3. **Event Subscription**: The client subscribes to a specific endpoint that exposes a stream of Server-Sent Events (SSE).
4. **Completion Notification**: Once the asynchronous task is finished and the file is ready, the server sends an event through the SSE connection.
5. **UI Update**: The client receives the event, which contains the URL to download the file, and dynamically updates the page to show the link to the user.

This approach, based on SSE and reactive programming with Mutiny in Quarkus, offers a simpler alternative to WebSockets for unidirectional server-to-client communication, significantly improving the user experience in scenarios with long-running processes.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/quarkus-sse-poc-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.
