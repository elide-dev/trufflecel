#!/usr/bin/env python3
"""Import the pinned cel-spec textproto corpus. Requires protoc and Python protobuf.

Usage: python tools/import_conformance.py /path/to/cel-spec
Generated JSON is test data only; the library has no JSON/protobuf dependency.
"""
import importlib
import json
import pathlib
import subprocess
import sys
import tempfile
import shutil
from google.protobuf import json_format, text_format

root = pathlib.Path(__file__).resolve().parents[1]
spec = pathlib.Path(sys.argv[1]).resolve()
revision = (root / 'third_party/cel-spec/REVISION').read_text().strip()
actual = subprocess.check_output(['git', '-C', str(spec), 'rev-parse', 'HEAD'], text=True).strip()
if actual != revision:
    raise SystemExit(f'Expected cel-spec {revision}, got {actual}')
for syntax in ('proto2', 'proto3'):
    source = spec / 'proto/cel/expr/conformance' / syntax
    destination = root / 'third_party/cel-spec/proto/cel/expr/conformance' / syntax
    destination.mkdir(parents=True, exist_ok=True)
    for proto in source.glob('*.proto'):
        shutil.copyfile(proto, destination / proto.name)
with tempfile.TemporaryDirectory() as generated:
    protos = list((spec / 'proto/cel/expr').glob('*.proto'))
    protos += list((spec / 'proto/cel/expr/conformance/proto2').glob('*.proto'))
    protos += list((spec / 'proto/cel/expr/conformance/proto3').glob('*.proto'))
    protos += [spec / 'proto/cel/expr/conformance/test/simple.proto']
    subprocess.run(['protoc', '-I', str(spec / 'proto'), f'--python_out={generated}', *map(str, protos)], check=True)
    sys.path.insert(0, generated)
    from cel.expr.conformance.test import simple_pb2
    from cel.expr.conformance.proto2 import test_all_types_pb2 as proto2
    from cel.expr.conformance.proto2 import test_all_types_extensions_pb2
    from cel.expr.conformance.proto3 import test_all_types_pb2 as proto3
    from google.protobuf import any_pb2, duration_pb2, timestamp_pb2, wrappers_pb2, struct_pb2
    corpus = []
    for file in sorted((spec / 'tests/simple/testdata').glob('*.textproto')):
        tests = text_format.Parse(file.read_text(), simple_pb2.SimpleTestFile())
        data = json_format.MessageToDict(tests, preserving_proto_field_name=True, use_integers_for_enums=True)
        data['file'] = file.stem
        corpus.append(data)
    (root / 'third_party/cel-spec/corpus.json').write_text(json.dumps(corpus, ensure_ascii=False, indent=2) + '\n')
    schemas = {}; enums = {}
    def message(desc):
        if desc.full_name in schemas: return
        fields = {}
        schemas[desc.full_name] = fields
        for f in desc.fields:
            fields[f.name] = dict(number=f.number, type=f.type, repeated=f.is_repeated, presence=f.has_presence, json_name=f.json_name)
            if f.message_type:
                fields[f.name]['message'] = f.message_type.full_name
                fields[f.name]['map'] = f.message_type.GetOptions().map_entry
                message(f.message_type)
            if f.enum_type:
                fields[f.name]['enum'] = f.enum_type.full_name
            if not f.message_type and not f.is_repeated:
                default = f.default_value
                if isinstance(default, bytes):
                    import base64
                    default = base64.b64encode(default).decode()
                fields[f.name]['default'] = default
        for nested in desc.nested_types: message(nested)
        for enum in desc.enum_types: enumeration(enum)
    def enumeration(desc):
        for v in desc.values:
            enums[desc.full_name + '.' + v.name] = v.number
            enums[desc.full_name.rsplit('.', 1)[0] + '.' + v.name] = v.number
    for module in (proto2, proto3):
        for desc in module.DESCRIPTOR.message_types_by_name.values(): message(desc)
        for desc in module.DESCRIPTOR.enum_types_by_name.values(): enumeration(desc)
    # Extensions share their extendee's schema, with fully-qualified field names.
    from google.protobuf import descriptor_pool
    for name, fields in list(schemas.items()):
        descriptor = descriptor_pool.Default().FindMessageTypeByName(name)
        for f in descriptor_pool.Default().FindAllExtensions(descriptor):
            entry = dict(number=f.number, type=f.type, repeated=f.is_repeated, presence=f.has_presence, json_name=f.json_name)
            if f.message_type:
                entry['message'] = f.message_type.full_name
                entry['map'] = f.message_type.GetOptions().map_entry
                message(f.message_type)
            if not f.message_type and not f.is_repeated:
                entry['default'] = f.default_value
            fields[f.full_name] = entry
    (root / 'third_party/cel-spec/schemas.json').write_text(json.dumps(dict(messages=schemas, enums=enums), indent=2) + '\n')
