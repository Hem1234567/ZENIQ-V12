#!/bin/sh
# Fetch llama.cpp (pinned) into the native source folder
git clone --depth 1 --branch b5618 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
rm -rf app/src/main/cpp/llama.cpp/.git
