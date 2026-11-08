#!/usr/bin/env python3
"""Paired ordinary-map and caller-prepared ImmutableMap controls; preparation excluded."""
import evaluation_refresh

ENGINES = ('cel-java-prepared-map', 'cel-java-optimized-prepared-map',
           'cel-java-immutable', 'cel-java-optimized-immutable')
WORKLOADS = ('scalar-wide', 'proto-policy')


if __name__ == '__main__':
    evaluation_refresh.main(
        engines=ENGINES, workloads=WORKLOADS,
        experiment='cel-java full-input reuse control: ordinary versus caller-prepared Guava ImmutableMap',
        entrypoint=__file__)
