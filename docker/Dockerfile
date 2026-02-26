FROM ubuntu:latest
LABEL maintainer="Courville Software <software@courville.org>"

ENV DEBIAN_FRONTEND=noninteractive
ENV USER=root

# install build tools, repo, and set up python
RUN apt-get update && apt-get upgrade -y && \
    apt-get install -y \
        nasm meson ninja-build maven build-essential \
        file git wget curl unzip sudo pkg-config \
        python3 python3-pip python3-setuptools python3-wheel \
        openjdk-17-jdk-headless \
        tig vim-tiny less screen && \
    apt-get clean autoclean && \
    rm -rf /var/lib/apt/lists/* && \
    update-alternatives --install /usr/bin/python python /usr/bin/python3 1 && \
    curl https://storage.googleapis.com/git-repo-downloads/repo | tee /usr/local/bin/repo > /dev/null && \
    chmod a+x /usr/local/bin/repo

# set proper env variables
ENV ANDROID_SDK_TOOLS=8092744 \
    OS=linux \
    SDK_URL="https://dl.google.com/android/repository"

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/cmdline-tools/bin"

# download and install minimal Android SDK
RUN echo "Install minimal Android SDK" && \
    mkdir -p ${ANDROID_SDK_ROOT} && \
    wget --quiet --output-document=/opt/android-cmdline.zip ${SDK_URL}/commandlinetools-${OS}-${ANDROID_SDK_TOOLS}_latest.zip && \
    unzip -q /opt/android-cmdline.zip -d ${ANDROID_SDK_ROOT} && \
    rm /opt/android-cmdline.zip

# install Android cmdline-tools;latest (uses bootstrap sdkmanager with --sdk_root)
RUN yes | sdkmanager --sdk_root=${ANDROID_SDK_ROOT} 'cmdline-tools;latest' > /dev/null

# install NDK, cmake, build-tools, platform-tools
# versions pinned explicitly (sdkmanager --list crashes under QEMU emulation)
# update these periodically to track latest stable releases
RUN yes | sdkmanager \
    'ndk;29.0.14206865' \
    'cmake;3.22.1' \
    'build-tools;36.0.0' \
    'platform-tools' > /dev/null

# set NDK/cmake environment and add NDK/LLVM toolchain to PATH via /etc/profile.d
# also write to /etc/environment for non-interactive shells
RUN NDK_PATH=$(ls -d ${ANDROID_SDK_ROOT}/ndk/* | sort -V | tail -n 1) && \
    CMAKE_PATH=$(ls -d ${ANDROID_SDK_ROOT}/cmake/*/bin | sort -V | tail -n 1) && \
    PREBUILT=prebuilt/linux-x86_64 && \
    echo "ANDROID_NDK_HOME=${NDK_PATH}" >> /etc/environment && \
    echo "ANDROID_NDK_ROOT=${NDK_PATH}" >> /etc/environment && \
    echo "export ANDROID_NDK_HOME=${NDK_PATH}" > /etc/profile.d/android.sh && \
    echo "export ANDROID_NDK_ROOT=${NDK_PATH}" >> /etc/profile.d/android.sh && \
    echo "export PATH=${NDK_PATH}/${PREBUILT}/bin:${NDK_PATH}/toolchains/llvm/${PREBUILT}/bin:${CMAKE_PATH}:\$PATH" >> /etc/profile.d/android.sh

# fetch nova source code
RUN mkdir -p /nova && \
    cd /nova && \
    repo init -u https://github.com/nova-video-player/aos-AVP -b nova -m v6_4.xml && \
    repo sync -j 4 && \
    repo forall -c 'git checkout -t $REPO_REMOTE/$REPO_RREV'

WORKDIR /nova

#ENTRYPOINT ["make"]

USER root
