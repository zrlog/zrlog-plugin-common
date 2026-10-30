#!/usr/bin/env python3
"""Prove snapshot deployment and clean consumer resolution using a local file repository."""

import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
version = ET.parse(ROOT / 'pom.xml').findtext('m:version', namespaces=NS)
if not version or not version.endswith('-SNAPSHOT'):
    raise SystemExit('Snapshot verification requires an explicit -SNAPSHOT version')

coordinates = {
    'zrlog-plugin-parent': [('pom', '')],
    'zrlog-plugin-common': [('pom', ''), ('jar', ''), ('jar', 'sources')],
    'zrlog-plugin-freemarker-render': [('pom', ''), ('jar', ''), ('jar', 'sources')],
}
with tempfile.TemporaryDirectory(prefix='zrlog-plugin-snapshot-') as temporary:
    work = Path(temporary)
    repository = work / 'repository'
    subprocess.run([
        str(ROOT / 'mvnw'), '-B', '-ntp', '-Psnapshot', 'deploy', '-DskipTests',
        '-DaltDeploymentRepository=local-proof::' + repository.as_uri(),
    ], cwd=ROOT, check=True)
    for artifact, required in coordinates.items():
        directory = repository / 'com/hibegin' / artifact / version
        metadata = ET.parse(directory / 'maven-metadata.xml')
        snapshots = metadata.findall('versioning/snapshotVersions/snapshotVersion')
        for extension, classifier in required:
            entry = next((entry for entry in snapshots
                          if entry.findtext('extension') == extension
                          and entry.findtext('classifier', '') == classifier), None)
            if entry is None:
                raise RuntimeError(f'Missing snapshot metadata: {artifact}:{extension}:{classifier}')
            suffix = '-' + classifier if classifier else ''
            output = directory / f'{artifact}-{entry.findtext("value")}{suffix}.{extension}'
            digest = hashlib.sha1(output.read_bytes()).hexdigest()
            if output.with_suffix(output.suffix + '.sha1').read_text().strip() != digest:
                raise RuntimeError(f'Checksum mismatch: {output.name}')

    consumer = work / 'consumer.xml'
    consumer.write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>test</groupId><artifactId>snapshot-consumer</artifactId><version>1</version>
  <repositories><repository><id>local-proof</id><url>{repository.as_uri()}</url>
    <releases><enabled>false</enabled></releases><snapshots><enabled>true</enabled></snapshots>
  </repository></repositories>
  <dependencies><dependency><groupId>com.hibegin</groupId>
    <artifactId>zrlog-plugin-freemarker-render</artifactId><version>{version}</version>
  </dependency></dependencies>
</project>''')
    # A new cache proves that neither the producer reactor nor previously installed snapshots supply the consumer.
    consumer_cache = work / 'consumer-m2'
    environment = dict(os.environ)
    environment.pop('MAVEN_ARGS', None)
    environment.pop('MAVEN_OPTS', None)
    subprocess.run([
        str(ROOT / 'mvnw'), '-B', '-ntp', '-f', str(consumer),
        '-Dmaven.repo.local=' + str(consumer_cache),
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve',
    ], cwd=ROOT, env=environment, check=True)
    for artifact, required in coordinates.items():
        directory = consumer_cache / 'com/hibegin' / artifact / version
        for extension, classifier in required:
            if classifier:
                continue
            if not (directory / f'{artifact}-{version}.{extension}').is_file():
                raise RuntimeError(f'Consumer did not resolve {artifact}:{extension}')
    print(f'Local snapshot deployment, checksums and clean consumer resolution passed: {version}')
