FROM mcr.microsoft.com/devcontainers/base:ubuntu24.04

# Java client baseline and Python tooling for the cross-language TCK bridge.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless \
        maven \
        make \
        python3 \
        python3-venv \
    && ln -s "/usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture)" /opt/java \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Official uv image supplies the package manager used by the TCK Makefile.
COPY --from=ghcr.io/astral-sh/uv:0.12.18 /uv /uvx /usr/local/bin/

# Fail the image build early if required tools are unavailable.
RUN java -version \
    && javac -version \
    && mvn --version \
    && python3 --version \
    && uv --version \
    && make --version
