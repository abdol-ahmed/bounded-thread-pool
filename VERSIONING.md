# Version Management

This project follows [Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## Version Format
- **MAJOR**: Incompatible API changes
- **MINOR**: New functionality in a backward compatible manner
- **PATCH**: Backward compatible bug fixes

## Current Version: 1.0.1

### Version History

#### 1.0.1 (Current)
- Migrated from Gradle to Maven build system
- Updated documentation for Maven Central publishing

#### 1.0.0
- Initial release
- Core BoundedThreadPool implementation
- BoundedBlockingQueue with configurable capacity
- Five rejection policies: BLOCK, ABORT, DISCARD, DISCARD_OLDEST, CALLER_RUNS
- Graceful shutdown (shutdown()) and immediate shutdown (shutdownNow())
- Factory methods for common configurations
- Comprehensive test suite

### Release Process

1. Update version in `pom.xml`
2. Update `CHANGELOG.md`
3. Commit changes with tag: `git tag -a v1.0.1 -m "Release version 1.0.1"`
4. Push to GitHub: `git push origin v1.0.1`
5. Deploy to Maven Central: `mvn clean deploy -P ossrh`

### Using Different Versions

#### Maven
```xml
<dependency>
    <groupId>io.github.abdol_ahmed.btp</groupId>
    <artifactId>bounded-thread-pool</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Version Compatibility

- **1.x.x**: Stable API with backward compatibility
- Breaking changes will increment MAJOR version
- All MINOR versions will be backward compatible
- PATCH versions for bug fixes only

### Branching Strategy

- `main`: Stable releases
- `develop`: Next development version
- `feature/*`: New features
- `hotfix/*`: Critical bug fixes
