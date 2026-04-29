#!/bin/bash

cat <<-EOS > CHANGELOG.md
# Nova Video Player changelog

$($HOME/go/bin/changelog-from-release -l 2)

EOS
