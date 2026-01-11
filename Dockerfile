FROM node:current-bookworm

ARG ANDROID_SDK_ROOT=/opt/android-sdk
ARG ANDROID_PLATFORM=35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG JAVA_HOME=/opt/java
ENV ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT} \
    ANDROID_HOME=${ANDROID_SDK_ROOT} \
    JAVA_HOME=${JAVA_HOME} \
    PATH=${PATH}:${JAVA_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    unzip \
    zip \
    python3 \
    build-essential \
    ca-certificates \
  && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${JAVA_HOME} \
  && curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jdk/hotspot/normal/eclipse" -o /tmp/jdk.tar.gz \
  && tar -xzf /tmp/jdk.tar.gz -C ${JAVA_HOME} --strip-components=1 \
  && rm /tmp/jdk.tar.gz

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
  && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline-tools.zip \
  && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
  && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
  && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses
RUN sdkmanager \
    "platform-tools" \
    "platforms;android-${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS}"

RUN npm install -g @react-native-community/cli \
  && (npm install -g @openai/codex || npm install -g codex)

RUN useradd -ms /bin/bash dev \
  && chown -R dev:dev ${ANDROID_SDK_ROOT}
WORKDIR /workspace
