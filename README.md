== Codegen cli

A runner tool for Vert.x Codegen processing.

== Build

Build with Maven : generates a fat jar `target/vertx-codegen-cli-3.2.0-SNAPSHOT.jar`

== Usage

```
Usage: <main class> [options] The list of maven artifact coordinates to generate
  Options:
    --codegen
       the codegen.js file path
    --target
       the root directory for generated files

```

Example:

```
java -jar target/vertx-codegen-cli-3.2.0-SNAPSHOT.jar --codegen /Users/julien/java/vertx-lang-js/src/main/resources/codegen.json --target generated io.vertx:vertx-core:3.2.0-SNAPSHOT
```

