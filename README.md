# git-proxy in Java
This is a simple implementation of a git proxy in Java. This is a possible successor to [finos/git-proxy](https://github.com/finos/git-proxy) which is written in Node.

## Usage
To use this project, you need to have Java 17 or higher installed on your machine. You can run the project using the following command:
```shell
./gradlew bootRun
```

## Endpoints
The proxy has support for GitHub, GitLab, and Bitbucket. The following endpoints are available which can be used to interact with an upstream git server:
- `/github.com/{owner}/{repo}`
- `/gitlab.com/{owner}/{repo}`
- `/bitbucket.org/{owner}/{repo}`

An example of how to use the proxy is as follows:
```shell
git clone http://localhost:8080/github.com/finos/git-proxy.git
```

## Demo

![demo 1](./static/jgit-proxy-demo.gif)

![demo 2](./static/jgit-proxy-demo2.gif)

Running the server
![demo 3](./static/jgit-proxy-demo3.gif)

