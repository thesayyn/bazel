    FileSystemUtils.writeLinesAs(file, UTF_8, content, "\n");
      // By using applyFuzzy, the patch also applies when there is an offset.
      newContent = patch.applyFuzzy(oldContent, 0);