# Contributing to Distributed Counter

Thank you for your interest in contributing to the Distributed Counter project! This document provides guidelines and information for contributors.

## 🎯 **Getting Started**

### **Prerequisites**
- Java 11 or higher
- Gradle 7.0 or higher
- Git

### **Development Setup**
1. Fork the repository
2. Clone your fork locally
3. Build the project: `./gradlew build`
4. Run tests: `./gradlew test`

## 🚀 **Development Workflow**

### **1. Create a Feature Branch**
```bash
git checkout -b feature/your-feature-name
```

### **2. Make Your Changes**
- Follow the existing code style
- Add tests for new functionality
- Update documentation as needed

### **3. Test Your Changes**
```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests YourTestClass

# Build the project
./gradlew build
```

### **4. Commit Your Changes**
```bash
git add .
git commit -m "Add feature: brief description of changes"
```

### **5. Push and Create Pull Request**
```bash
git push origin feature/your-feature-name
```

## 📝 **Code Style Guidelines**

### **Java Code Style**
- Use 4 spaces for indentation
- Follow Java naming conventions
- Add meaningful comments for complex logic
- Keep methods focused and concise

### **Documentation**
- Update README.md for user-facing changes
- Add Javadoc for public methods
- Update docs/ for architectural changes

### **Testing**
- Write unit tests for new functionality
- Ensure test coverage for critical paths
- Use descriptive test method names

## 🏗️ **Project Structure**

```
src/
├── main/java/com/distributedcounter/
│   ├── ShardNode.java                 # Shard node implementation
│   ├── ShardedCounterCoordinator.java # Coordinator implementation
│   ├── client/                        # Client libraries
│   ├── hashing/                       # Consistent hashing
│   ├── model/                         # Data models
│   ├── replication/                   # Read replica support
│   └── storage/                       # Persistence layer
├── test/java/com/distributedcounter/  # Test classes
└── resources/                         # Configuration files
```

## 🧪 **Testing Guidelines**

### **Unit Tests**
- Test individual components in isolation
- Mock external dependencies
- Test both success and failure scenarios

### **Integration Tests**
- Test component interactions
- Test end-to-end workflows
- Test with real RocksDB instances

### **Performance Tests**
- Test high-throughput scenarios
- Measure latency and throughput
- Test with multiple shards

## 📊 **Performance Considerations**

### **When Adding Features**
- Consider impact on write performance
- Consider impact on read performance
- Test with realistic load patterns
- Monitor memory usage

### **Benchmarking**
```bash
# Run performance tests
./gradlew test --tests PerformanceTest
```

## 🚨 **Common Issues**

### **Build Failures**
- Ensure Java 11+ is installed
- Clean and rebuild: `./gradlew clean build`
- Check for dependency conflicts

### **Test Failures**
- Check RocksDB data directory permissions
- Ensure ports are available for tests
- Check system resources

## 🤝 **Pull Request Guidelines**

### **Before Submitting**
- [ ] Code builds successfully
- [ ] All tests pass
- [ ] Documentation is updated
- [ ] Code follows style guidelines
- [ ] Performance impact is considered

### **Pull Request Template**
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Documentation update
- [ ] Performance improvement

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests pass
- [ ] Performance tests pass

## Documentation
- [ ] README updated
- [ ] API documentation updated
- [ ] Architecture docs updated
```

## 📚 **Documentation**

### **When to Update Documentation**
- New features or APIs
- Architecture changes
- Configuration changes
- Performance improvements

### **Documentation Standards**
- Use clear, concise language
- Include code examples
- Add diagrams for complex concepts
- Keep documentation up-to-date

## 🎯 **Areas for Contribution**

### **High Priority**
- Performance optimizations
- Additional test coverage
- Documentation improvements
- Bug fixes

### **Medium Priority**
- New features
- API enhancements
- Monitoring improvements
- Tooling improvements

### **Low Priority**
- Cosmetic changes
- Minor refactoring
- Additional examples

## 📞 **Getting Help**

### **Questions and Issues**
- Create an issue on GitHub
- Use issue templates
- Provide detailed information
- Include logs and error messages

### **Discussion**
- Use GitHub Discussions
- Join community channels
- Share ideas and proposals

## 🙏 **Acknowledgments**

Thank you for contributing to the Distributed Counter project! Your contributions help make this project better for everyone.

---

**Happy coding! 🚀** 