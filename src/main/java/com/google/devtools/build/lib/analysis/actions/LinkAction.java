// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SymlinkTargetType;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Action that declares its output to be a content <em>link</em> of its input: the same content,
 * re-addressed at the output's exec path, sharing the input's digest, with no byte copy and no
 * spawn.
 *
 * <p>This is deliberately <em>not</em> a {@link SymlinkAction}. A symlink is realized on disk as a
 * followable symbolic link; tools that resolve symlinks (e.g. Node.js {@code realpath}) then escape
 * out of the tree the link lives in. A content link's contract is the opposite: it is realized as
 * <em>content</em> -- a hard link, copy, reflink, or remote digest reuse -- so that its {@code
 * realpath} is stable within the tree that consumes it.
 *
 * <p><b>Laydown is the execution strategy's decision, not this action's.</b> Analogously to path
 * mapping, this action only <em>declares</em> the relation and leaves a resolvable, zero-byte
 * backing in the output tree; each execution strategy then materializes the content however it
 * stages inputs (hard link, copy, reflink, bind mount, or -- on remote execution -- by reusing the
 * shared digest). Strategies that cannot lay content down (a purely symlinking sandbox, or
 * non-sandboxed local execution) do not honor the contract. Rules relying on it are expected to run
 * under a content-materializing strategy; supporting every strategy and tool is an explicit
 * non-goal.
 */
public final class LinkAction extends AbstractAction {
  private static final String GUID = "9a3b0c2e-5f7d-4a1b-9c6e-2d8f0a1b3c4d";

  @Nullable private final String progressMessage;

  /**
   * Creates an action declaring {@code output} to be a content link of {@code input}.
   *
   * @param owner the action owner
   * @param input the artifact whose content is re-addressed
   * @param output the artifact that will be created by executing this action
   * @param progressMessage the progress message
   */
  public static LinkAction create(
      ActionOwner owner, Artifact input, Artifact output, String progressMessage) {
    return new LinkAction(owner, input, output, progressMessage);
  }

  private LinkAction(
      ActionOwner owner, Artifact primaryInput, Artifact primaryOutput, String progressMessage) {
    super(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, checkNotNull(primaryInput)),
        ImmutableSet.of(primaryOutput));
    this.progressMessage = progressMessage;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    Path outputPath = actionExecutionContext.getInputPath(getPrimaryOutput());
    Path targetPath = actionExecutionContext.getInputPath(getPrimaryInput());

    try {
      outputPath.delete();
      // Leave a resolvable, zero-byte backing in the output tree. This is NOT the tool-visible
      // laydown: content-materializing strategies (hardlinked/copying/hermetic sandbox; remote
      // execution via the shared digest below) resolve this to real content at the consumer. A
      // purely symlinking strategy will not -- an accepted limitation.
      outputPath.createSymbolicLink(targetPath, getSymlinkTargetType(actionExecutionContext));
    } catch (IOException e) {
      String message =
          String.format(
              "failed to create content link '%s' to '%s' due to I/O error: %s",
              getPrimaryOutput().getExecPathString(), getPrimaryInput().getExecPathString(),
              e.getMessage());
      throw new ActionExecutionException(
          message, e, this, false, createDetailedExitCode(message));
    }

    // Forward the input's metadata (and digest) to the output so downstream consumers -- especially
    // remote execution -- see the shared content and never re-upload or re-hash it. Reuses the same
    // helper SymlinkAction uses.
    SymlinkAction.maybeInjectMetadata(this, actionExecutionContext);
    return ActionResult.EMPTY;
  }

  private SymlinkTargetType getSymlinkTargetType(ActionExecutionContext actionExecutionContext)
      throws IOException {
    FileArtifactValue metadata =
        checkNotNull(
            actionExecutionContext.getInputMetadataProvider().getInputMetadata(getPrimaryInput()),
            "missing metadata for %s",
            getPrimaryInput());
    return metadata.getType() == FileStateType.DIRECTORY
        ? SymlinkTargetType.DIRECTORY
        : SymlinkTargetType.FILE;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp) {
    fp.addString(GUID);
  }

  @Override
  protected String getRawProgressMessage() {
    return progressMessage;
  }

  @Override
  public String getMnemonic() {
    return "Link";
  }

  @Override
  public boolean mayInsensitivelyPropagateInputs() {
    return true;
  }

  @Override
  public PlatformInfo getExecutionPlatform() {
    return PlatformInfo.EMPTY_PLATFORM_INFO;
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    return ImmutableMap.of();
  }

  private static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setSymlinkAction(
                FailureDetails.SymlinkAction.newBuilder()
                    .setCode(FailureDetails.SymlinkAction.Code.LINK_CREATION_IO_EXCEPTION))
            .build());
  }
}
