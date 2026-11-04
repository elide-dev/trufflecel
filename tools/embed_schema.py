#!/usr/bin/env python3
"""Embed Bazel-compiled descriptor sets without importing Python protobuf."""
import base64
import pathlib
import sys

chunks = [base64.b64encode(pathlib.Path(p).read_bytes()).decode() for p in sys.argv[2:]]
encoded = ',\n'.join('"' + c + '"' for c in chunks)
pathlib.Path(sys.argv[1]).write_text('''package dev.trufflecel;
import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import java.util.*;
/** Generated from the pinned cel-spec schemas. Copyright 2022-2023 Google LLC.
 * Licensed under Apache-2.0; see META-INF/LICENSE-APACHE. */
final class CanonicalSchema {
  static final Map<String, Descriptor> MESSAGES = build();
  private static Map<String, Descriptor> build() {
    try {
      var pending = new LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>();
      for (String data : new String[]{''' + encoded + '''})
        for (var file : DescriptorProtos.FileDescriptorSet.parseFrom(Base64.getDecoder().decode(data)).getFileList())
          pending.put(file.getName(), file);
      var files = new LinkedHashMap<String, FileDescriptor>();
      while (!pending.isEmpty()) {
        int before = pending.size();
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
          var file = iterator.next();
          if (!files.keySet().containsAll(file.getDependencyList())) continue;
          FileDescriptor[] deps = file.getDependencyList().stream().map(files::get).toArray(FileDescriptor[]::new);
          files.put(file.getName(), FileDescriptor.buildFrom(file, deps));
          iterator.remove();
        }
        if (before == pending.size()) throw new IllegalStateException("Unresolved schema dependencies");
      }
      var messages = new HashMap<String, Descriptor>();
      for (var file : files.values()) for (var message : file.getMessageTypes()) add(message, messages);
      return Map.copyOf(messages);
    } catch (java.io.IOException | DescriptorValidationException e) { throw new ExceptionInInitializerError(e); }
  }
  private static void add(Descriptor message, Map<String, Descriptor> messages) {
    messages.put(message.getFullName(), message);
    for (var child : message.getNestedTypes()) add(child, messages);
  }
}
''')
