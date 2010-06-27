/*
 * Copyright (C) 2008-2010, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.pack.PackWriter;

/**
 * Creates a Git bundle file, for sneaker-net transport to another system.
 * <p>
 * Bundles generated by this class can be later read in from a file URI using
 * the bundle transport, or from an application controlled buffer by the more
 * generic {@link TransportBundleStream}.
 * <p>
 * Applications creating bundles need to call one or more <code>include</code>
 * calls to reflect which objects should be available as refs in the bundle for
 * the other side to fetch. At least one include is required to create a valid
 * bundle file, and duplicate names are not permitted.
 * <p>
 * Optional <code>assume</code> calls can be made to declare commits which the
 * recipient must have in order to fetch from the bundle file. Objects reachable
 * from these assumed commits can be used as delta bases in order to reduce the
 * overall bundle size.
 */
public class BundleWriter {
	private final PackWriter packWriter;

	private final Map<String, ObjectId> include;

	private final Set<RevCommit> assume;

	/**
	 * Create a writer for a bundle.
	 *
	 * @param repo
	 *            repository where objects are stored.
	 * @param monitor
	 *            operations progress monitor.
	 */
	public BundleWriter(final Repository repo, final ProgressMonitor monitor) {
		packWriter = new PackWriter(repo, monitor);
		include = new TreeMap<String, ObjectId>();
		assume = new HashSet<RevCommit>();
	}

	/**
	 * Include an object (and everything reachable from it) in the bundle.
	 *
	 * @param name
	 *            name the recipient can discover this object as from the
	 *            bundle's list of advertised refs . The name must be a valid
	 *            ref format and must not have already been included in this
	 *            bundle writer.
	 * @param id
	 *            object to pack. Multiple refs may point to the same object.
	 */
	public void include(final String name, final AnyObjectId id) {
		if (!Repository.isValidRefName(name))
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidRefName, name));
		if (include.containsKey(name))
			throw new IllegalStateException(JGitText.get().duplicateRef + name);
		include.put(name, id.toObjectId());
	}

	/**
	 * Include a single ref (a name/object pair) in the bundle.
	 * <p>
	 * This is a utility function for:
	 * <code>include(r.getName(), r.getObjectId())</code>.
	 *
	 * @param r
	 *            the ref to include.
	 */
	public void include(final Ref r) {
		include(r.getName(), r.getObjectId());
	}

	/**
	 * Assume a commit is available on the recipient's side.
	 * <p>
	 * In order to fetch from a bundle the recipient must have any assumed
	 * commit. Each assumed commit is explicitly recorded in the bundle header
	 * to permit the recipient to validate it has these objects.
	 *
	 * @param c
	 *            the commit to assume being available. This commit should be
	 *            parsed and not disposed in order to maximize the amount of
	 *            debugging information available in the bundle stream.
	 */
	public void assume(final RevCommit c) {
		if (c != null)
			assume.add(c);
	}

	/**
	 * Generate and write the bundle to the output stream.
	 * <p>
	 * This method can only be called once per BundleWriter instance.
	 *
	 * @param os
	 *            the stream the bundle is written to. The stream should be
	 *            buffered by the caller. The caller is responsible for closing
	 *            the stream.
	 * @throws IOException
	 *             an error occurred reading a local object's data to include in
	 *             the bundle, or writing compressed object data to the output
	 *             stream.
	 */
	public void writeBundle(OutputStream os) throws IOException {
		final HashSet<ObjectId> inc = new HashSet<ObjectId>();
		final HashSet<ObjectId> exc = new HashSet<ObjectId>();
		inc.addAll(include.values());
		for (final RevCommit r : assume)
			exc.add(r.getId());
		packWriter.setThin(exc.size() > 0);
		packWriter.preparePack(inc, exc);

		final Writer w = new OutputStreamWriter(os, Constants.CHARSET);
		w.write(TransportBundle.V2_BUNDLE_SIGNATURE);
		w.write('\n');

		final char[] tmp = new char[Constants.OBJECT_ID_STRING_LENGTH];
		for (final RevCommit a : assume) {
			w.write('-');
			a.copyTo(tmp, w);
			if (a.getRawBuffer() != null) {
				w.write(' ');
				w.write(a.getShortMessage());
			}
			w.write('\n');
		}
		for (final Map.Entry<String, ObjectId> e : include.entrySet()) {
			e.getValue().copyTo(tmp, w);
			w.write(' ');
			w.write(e.getKey());
			w.write('\n');
		}

		w.write('\n');
		w.flush();
		packWriter.writePack(os);
	}
}
