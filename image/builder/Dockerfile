FROM --platform=linux/amd64 ubuntu:24.04@sha256:2198e4c65076e9cb0b3d695219df667c0e3b0072da64ca3c2648aca17dcecaf5
ENV DEBIAN_FRONTEND=noninteractive
# CI can prefer its cloud-local Ubuntu mirror, with upstream mirrors as fallback.
# Use HTTP until ca-certificates is installed; APT still verifies Ubuntu signatures.
ARG UBUNTU_MIRROR=http://archive.ubuntu.com/ubuntu/
RUN printf '%s\tpriority:1\n%s\tpriority:2\n%s\tpriority:3\n' \
      "$UBUNTU_MIRROR" http://archive.ubuntu.com/ubuntu/ http://security.ubuntu.com/ubuntu/ \
      > /etc/apt/taku-mirrors.txt \
    && sed -i -E 's#http://(archive|security)\.ubuntu\.com/ubuntu/?([[:space:]]|$)#mirror+file:/etc/apt/taku-mirrors.txt\2#g' /etc/apt/sources.list.d/ubuntu.sources \
    && printf '%s\n' 'Acquire::Retries "1";' 'Acquire::http::Timeout "15";' 'Acquire::https::Timeout "15";' \
      > /etc/apt/apt.conf.d/zz-taku-network \
    && apt-get update --error-on=any && apt-get install -y --no-install-recommends \
    git git-lfs ca-certificates curl python3 unzip zip rsync bc bison \
    build-essential ccache flex g++-multilib gcc-multilib gnupg gperf \
    imagemagick protobuf-compiler python3-protobuf lib32readline-dev lib32z1-dev \
    libdw-dev libelf-dev libgnutls28-dev lz4 libssl-dev libxml2-utils lzop \
    pngcrush schedtool squashfs-tools xsltproc zlib1g-dev file \
    && rm -rf /var/lib/apt/lists/*
RUN curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 'https://gerrit.googlesource.com/git-repo/+/b1a1cc603a26026466ba25bb4c294e9b731e8f33/repo?format=TEXT' -o /tmp/repo.base64 \
    && base64 --decode /tmp/repo.base64 > /usr/local/bin/repo \
    && echo '05f571deafd6ed6da0d20fa707dde29aa5624dbf35553d697a40dbdd4c8d178a  /usr/local/bin/repo' | sha256sum --check \
    && chmod 755 /usr/local/bin/repo
WORKDIR /src
CMD ["bash"]
