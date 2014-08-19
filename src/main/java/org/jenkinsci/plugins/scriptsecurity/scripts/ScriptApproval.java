/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.scripts;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AclAwareWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Item;
import hudson.model.RootAction;
import hudson.model.Saveable;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.XStream2;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import net.sf.json.JSON;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.bind.JavaScriptMethod;

/**
 * Manages approved scripts.
 */
@Extension public class ScriptApproval implements RootAction, Saveable {

    private static final Logger LOG = Logger.getLogger(ScriptApproval.class.getName());

    private static final XStream2 XSTREAM2 = new XStream2();
    static {
        // Compatibility:
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval", ScriptApproval.class);
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval$PendingScript", PendingScript.class);
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval$PendingSignature", PendingSignature.class);
        // Current:
        XSTREAM2.alias("scriptApproval", ScriptApproval.class);
        XSTREAM2.alias("approvedClasspath", ApprovedClasspath.class); // TODO these need to be renamed to reflect that they are classpath entries, not classpaths
        XSTREAM2.alias("pendingScript", PendingScript.class);
        XSTREAM2.alias("pendingSignature", PendingSignature.class);
        XSTREAM2.alias("pendingClasspath", PendingClasspath.class);
    }

    /** Gets the singleton instance. */
    public static ScriptApproval get() {
        return Jenkins.getInstance().getExtensionList(RootAction.class).get(ScriptApproval.class);
    }

    /**
     * Approved classpath
     * 
     * It is treated only with the hash,
     * but additional information is provided for convenience.
     */
    @Restricted(NoExternalUse.class) // for use from Jelly and tests
    public static class ApprovedClasspath {
        private final String hash;
        private final URL url;
        
        public ApprovedClasspath(String hash, URL url) {
            this.hash = hash;
            this.url = url;
        }
        
        public String getHash() {
            return hash;
        }
        
        public URL getURL() {
            return url;
        }
    }
    
    /** All scripts which are already approved, via {@link #hash}. */
    private final Set<String> approvedScriptHashes = new TreeSet<String>();

    /** All sandbox signatures which are already whitelisted, in {@link StaticWhitelist} format. */
    private final Set<String> approvedSignatures = new TreeSet<String>();

    /** All sandbox signatures which are already whitelisted for ACL-only use, in {@link StaticWhitelist} format. */
    private /*final*/ Set<String> aclApprovedSignatures;

    /** All external classpaths allowed used for scripts. Keys are hashes.*/
    private /*final*/ Map<String, ApprovedClasspath> approvedClasspathMap /*= new LinkedHashMap<String, ApprovedClasspath>()*/;

    private Map<String,ApprovedClasspath> getApprovedClasspathMap() {
        return approvedClasspathMap;
    }

    private boolean hasApprovedClasspath(String hash) {
        return getApprovedClasspathMap().containsKey(hash);
    }

    private ApprovedClasspath getApprovedClasspath(String hash) {
        return getApprovedClasspathMap().get(hash);
    }

    /**
     * @return true if added
     */
    boolean addApprovedClasspath(ApprovedClasspath acp) {
        if (hasApprovedClasspath(acp.getHash())) {
            return false;
        }
        getApprovedClasspathMap().put(acp.getHash(), acp);
        return true;
    }

    private boolean removeApprovedClasspath(String hash) {
        return getApprovedClasspathMap().remove(hash) != null;
    }

    private void removeAllApprovedClasspath() {
        getApprovedClasspathMap().clear();
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static abstract class PendingThing {

        /** @deprecated only used from historical records */
        @Deprecated private String user;
        
        private @Nonnull ApprovalContext context;

        PendingThing(@Nonnull ApprovalContext context) {
            this.context = context;
        }

        public @Nonnull ApprovalContext getContext() {
            return context;
        }

        private Object readResolve() {
            if (user != null) {
                context = ApprovalContext.create().withUser(user);
                user = null;
            }
            return this;
        }

    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingScript extends PendingThing {
        public final String script;
        private final String language;
        PendingScript(@Nonnull String script, @Nonnull Language language, @Nonnull ApprovalContext context) {
            super(context);
            this.script = script;
            this.language = language.getName();
        }
        public String getHash() {
            return hash(script, language);
        }
        public Language getLanguage() {
            for (Language l : Jenkins.getInstance().getExtensionList(Language.class)) {
                if (l.getName().equals(language)) {
                    return l;
                }
            }
            return new Language() {
                @Override public String getName() {
                    return language;
                }
                @Override public String getDisplayName() {
                    return "<missing language: " + language + ">";
                }
            };
        }
        @Override public int hashCode() {
            return script.hashCode() ^ language.hashCode();
        }
        @Override public boolean equals(Object obj) {
            // Intentionally do not consider context in equality check.
            return obj instanceof PendingScript && ((PendingScript) obj).language.equals(language) && ((PendingScript) obj).script.equals(script);
        }
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingSignature extends PendingThing {
        public final String signature;
        PendingSignature(@Nonnull String signature, @Nonnull ApprovalContext context) {
            super(context);
            this.signature = signature;
        }
        public String getHash() {
            // Security important, just for UI:
            return Integer.toHexString(hashCode());
        }
        @Override public int hashCode() {
            return signature.hashCode();
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof PendingSignature && ((PendingSignature) obj).signature.equals(signature);
        }
    }

    /**
     * A classpath requiring approval by an administrator.
     * 
     * They are distinguished only with hashes,
     * but other additional information is provided for users.
     */
    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingClasspath extends PendingThing {
        private final URL url;
        private final String hash;
        
        PendingClasspath(@Nonnull String hash, @Nonnull URL url, @Nonnull ApprovalContext context) {
            super(context);
            /**
             * hash should be stored as files located at the classpath can be modified.
             */
            this.hash = hash;
            this.url = url;
        }
        
        public String getHash() {
            return hash;
        }
        
        public URL getURL() {
            return url;
        }
        @Override public int hashCode() {
            // classpaths are distinguished only with its hash.
            return getHash().hashCode();
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof PendingClasspath && ((PendingClasspath) obj).getHash().equals(getHash());
        }
    }

    private final Set<PendingScript> pendingScripts = new LinkedHashSet<PendingScript>();

    private final Set<PendingSignature> pendingSignatures = new LinkedHashSet<PendingSignature>();

    private /*final*/ Map<String, PendingClasspath> pendingClasspathMap /*= new LinkedHashMap<String, PendingClasspath>()*/;

    private Map<String, PendingClasspath> getPendingClasspathMap() {
        return pendingClasspathMap;
    }

    private boolean hasPendingClasspath(String hash) {
        return getPendingClasspathMap().containsKey(hash);
    }

    private PendingClasspath getPendingClasspath(String hash) {
        return getPendingClasspathMap().get(hash);
    }

    /**
     * @param pcp
     * @return true if added
     */
    boolean addPendingClasspath(PendingClasspath pcp) {
        if (hasPendingClasspath(pcp.getHash())) {
            return false;
        }
        getPendingClasspathMap().put(pcp.getHash(), pcp);
        return true;
    }

    /**
     * @param hash
     * @return true if removed
     */
    private boolean removePendingClasspath(String hash) {
        return getPendingClasspathMap().remove(hash) != null;
    }

    public ScriptApproval() {
        try {
            load();
        } catch (IOException x) {
            LOG.log(Level.WARNING, null, x);
        }
        /* can be null when upgraded from old versions.*/
        if (aclApprovedSignatures == null) {
            aclApprovedSignatures = new TreeSet<String>();
        }
        if (approvedClasspathMap == null) {
            approvedClasspathMap = new LinkedHashMap<String, ApprovedClasspath>();
        }
        if (pendingClasspathMap == null) {
            pendingClasspathMap = new LinkedHashMap<String, PendingClasspath>();
        }
    }

    private static String hash(String script, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(language.getBytes("UTF-8"));
            digest.update((byte) ':');
            digest.update(script.getBytes("UTF-8"));
            return Util.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException x) {
            throw new AssertionError(x);
        } catch (UnsupportedEncodingException x) {
            throw new AssertionError(x);
        }
    }

    /** Creates digest of JAR contents. */
    private static String hashClasspathEntry(URL entry) throws IOException {
        InputStream is = entry.openStream();
        try {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                DigestInputStream input = new DigestInputStream(new BufferedInputStream(is), digest);
                byte[] buffer = new byte[1024];
                while (input.read(buffer) != -1) {
                    // discard
                }
                return Util.toHexString(digest.digest());
            } catch (NoSuchAlgorithmException x) {
                throw new AssertionError(x);
            }
        } finally {
            is.close();
        }
    }

    /**
     * Used when someone is configuring a script.
     * Typically you would call this from a {@link DataBoundConstructor}.
     * It should also be called from a {@code readResolve} method (which may then simply return {@code this}),
     * so that administrators can for example POST to {@code config.xml} and have their scripts be considered approved.
     * <p>If the script has already been approved, this does nothing.
     * Otherwise, if this user has the {@link Jenkins#RUN_SCRIPTS} permission (and is not {@link ACL#SYSTEM}), or Jenkins is running without security, it is added to the approved list.
     * Otherwise, it is added to the pending list.
     * @param script the text of a possibly novel script
     * @param language the language in which it is written
     * @param context any additional information about how where or by whom this is being configured
     * @return {@code script}, for convenience
     */
    public synchronized String configuring(@Nonnull String script, @Nonnull Language language, @Nonnull ApprovalContext context) {
        String hash = hash(script, language.getName());
        if (!approvedScriptHashes.contains(hash)) {
            if (!Jenkins.getInstance().isUseSecurity() || Jenkins.getAuthentication() != ACL.SYSTEM && Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS)) {
                approvedScriptHashes.add(hash);
            } else {
                String key = context.getKey();
                if (key != null) {
                    Iterator<PendingScript> it = pendingScripts.iterator();
                    while (it.hasNext()) {
                        if (key.equals(it.next().getContext().getKey())) {
                            it.remove();
                        }
                    }
                }
                pendingScripts.add(new PendingScript(script, language, context));
            }
            try {
                save();
            } catch (IOException x) {
                LOG.log(Level.WARNING, null, x);
            }
        }
        return script;
    }

    /**
     * Called when a script is about to be used (evaluated).
     * @param script a possibly unapproved script
     * @param language the language in which it is written
     * @return {@code script}, for convenience
     * @throws UnapprovedUsageException in case it has not yet been approved
     */
    public synchronized String using(@Nonnull String script, @Nonnull Language language) throws UnapprovedUsageException {
        if (script.length() == 0) {
            // As a special case, always consider the empty script preapproved, as this is usually the default for new fields,
            // and in many cases there is some sensible behavior for an emoty script which we want to permit.
            return script;
        }
        String hash = hash(script, language.getName());
        if (!approvedScriptHashes.contains(hash)) {
            // Probably need not add to pendingScripts, since generally that would have happened already in configuring.
            throw new UnapprovedUsageException(hash);
        }
        return script;
    }

    /**
     * Check whether classpath is approved. if not, add it as pending.
     * 
     * @param path
     * @param context
     */
    public synchronized void configuringClasspath(@Nonnull URL url, @Nonnull ApprovalContext context) {
        String hash;
        try {
            hash = hashClasspathEntry(url);
        } catch (IOException x) {
            // This is a case the path doesn't really exist
            LOG.log(Level.WARNING, null, x);
            return;
        }
        
        if (!hasApprovedClasspath(hash)) {
            boolean shouldSave = false;
            if (!Jenkins.getInstance().isUseSecurity() || (Jenkins.getAuthentication() != ACL.SYSTEM && Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS))) {
                LOG.log(Level.FINE, "Classpath {0} ({1}) is approved as configured with RUN_SCRIPTS permission.", new Object[] {url, hash});
                removePendingClasspath(hash);
                addApprovedClasspath(new ApprovedClasspath(hash, url));
                shouldSave = true;
            } else {
                if (addPendingClasspath(new PendingClasspath(hash, url, context))) {
                    LOG.log(Level.FINE, "{0} ({1}) is pending", new Object[] {url, hash});
                    shouldSave = true;
                }
            }
            if (shouldSave) {
                try {
                    save();
                } catch (IOException x) {
                    LOG.log(Level.WARNING, null, x);
                }
            }
        }
    }
    
    /**
     * @param path
     * @return whether a classpath is approved.
     * @throws IOException when failed to access classpath.
     */
    public synchronized boolean isClasspathApproved(@Nonnull URL url) throws IOException {
        String hash = hashClasspathEntry(url);
        
        return hasApprovedClasspath(hash);
    }
    
    private static Item currentExecutingItem() {
        if (Executor.currentExecutor() == null) {
            return null;
        }
        Queue.Executable exe = Executor.currentExecutor().getCurrentExecutable();
        if (exe == null || !(exe instanceof AbstractBuild)) {
            return null;
        }
        AbstractBuild<?,?> build = (AbstractBuild<?,?>)exe;
        AbstractProject<?,?> project = build.getParent();
        return project.getRootProject();
    }
    
    /**
     * Asserts a classpath is approved. Also records it as a pending classpath if not approved.
     * 
     * @param path classpath
     * @throws IOException when failed to access classpath.
     * @throws UnapprovedClasspathException when the classpath is not approved.
     */
    public synchronized void checkClasspathApproved(@Nonnull URL url) throws IOException, UnapprovedClasspathException {
        String hash = hashClasspathEntry(url);
        
        if (!hasApprovedClasspath(hash)) {
            // Never approve classpath here.
            ApprovalContext context = ApprovalContext.create();
            context = context.withCurrentUser().withItemAsKey(currentExecutingItem());
            if (addPendingClasspath(new PendingClasspath(hash, url, context))) {
                LOG.log(Level.FINE, "{0} ({1}) is pending.", new Object[] {url, hash});
                try {
                    save();
                } catch (IOException x) {
                    LOG.log(Level.WARNING, null, x);
                }
            }
            throw new UnapprovedClasspathException(url, hash);
        }
        
        LOG.log(Level.FINER, "{0} ({1}) had been approved as {2}", new Object[] {url, hash, getApprovedClasspath(hash).getURL()});
    }

    /**
     * To be used from form validation, in a {@code doCheckFieldName} method.
     * @param script a possibly unapproved script
     * @param language the language in which it is written
     * @return a warning in case the script is not yet approved and this user lacks {@link Jenkins#RUN_SCRIPTS}, else {@link FormValidation#ok()}
     */
    public synchronized FormValidation checking(@Nonnull String script, @Nonnull Language language) {
        if (!Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS) && !approvedScriptHashes.contains(hash(script, language.getName()))) {
            return FormValidation.warningWithMarkup("A Jenkins administrator will need to approve this script before it can be used.");
        } else {
            return FormValidation.ok();
        }
    }

    /**
     * Unconditionally approve a script.
     * Does no access checks and does not automatically save changes to disk.
     * Useful mainly for testing.
     * @param script the text of a possibly novel script
     * @param language the language in which it is written
     * @return {@code script}, for convenience
     */
    public synchronized String preapprove(@Nonnull String script, @Nonnull Language language) {
        approvedScriptHashes.add(hash(script, language.getName()));
        return script;
    }

    /**
     * Unconditionally approves all pending scripts.
     * Does no access checks and does not automatically save changes to disk.
     * Useful mainly for testing in combination with {@code @LocalData}.
     */
    public synchronized void preapproveAll() {
        for (PendingScript ps : pendingScripts) {
            approvedScriptHashes.add(ps.getHash());
        }
        pendingScripts.clear();
    }

    /**
     * To be called when a sandbox rejects access for a script not using manual approval.
     * The signature of the failing method (if known) will be added to the pending list.
     * @param x an exception with the details
     * @param context any additional information about where or by whom this script was run
     * @return {@code x}, for convenience in rethrowing
     */
    public synchronized RejectedAccessException accessRejected(@Nonnull RejectedAccessException x, @Nonnull ApprovalContext context) {
        String signature = x.getSignature();
        if (signature != null && pendingSignatures.add(new PendingSignature(signature, context))) {
            try {
                save();
            } catch (IOException x2) {
                LOG.log(Level.WARNING, null, x2);
            }
        }
        return x;
    }

    @Restricted(NoExternalUse.class) // Jelly, implementation
    public synchronized String[] getApprovedSignatures() {
        return approvedSignatures.toArray(new String[approvedSignatures.size()]);
    }

    @Restricted(NoExternalUse.class) // Jelly, implementation
    public synchronized String[] getAclApprovedSignatures() {
        return aclApprovedSignatures.toArray(new String[aclApprovedSignatures.size()]);
    }

    @Restricted(NoExternalUse.class) // implementation
    @Extension public static final class ApprovedWhitelist extends ProxyWhitelist {
        public ApprovedWhitelist() throws IOException {
            reconfigure();
        }
        String[][] reconfigure() throws IOException {
            ScriptApproval instance = ScriptApproval.get();
            synchronized (instance) {
                reset(Collections.singleton(new AclAwareWhitelist(new StaticWhitelist(instance.approvedSignatures), new StaticWhitelist(instance.aclApprovedSignatures))));
                return new String[][] {instance.getApprovedSignatures(), instance.getAclApprovedSignatures()};
            }
        }
    }

    @Override public String getIconFileName() {
        return null;
    }

    @Override public String getDisplayName() {
        return null;
    }

    @Override public String getUrlName() {
        return "scriptApproval";
    }

    private XmlFile getConfigFile() {
        return new XmlFile(XSTREAM2, new File(Jenkins.getInstance().getRootDir(), getUrlName() + ".xml"));
    }

    private synchronized void load() throws IOException {
        XmlFile xml = getConfigFile();
        if (xml.exists()) {
            xml.unmarshal(this);
        }
    }

    @Override public synchronized void save() throws IOException {
        getConfigFile().write(this);
        // TBD: outside synch block: SaveableListener.fireOnChange(this, getConfigFile());
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public Set<PendingScript> getPendingScripts() {
        return pendingScripts;
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public void approveScript(String hash) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        synchronized (this) {
            approvedScriptHashes.add(hash);
            removePendingScript(hash);
            save();
        }
        SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
        try {
            for (ApprovalListener listener : Jenkins.getInstance().getExtensionList(ApprovalListener.class)) {
                listener.onApproved(hash);
            }
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void denyScript(String hash) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        approvedScriptHashes.remove(hash);
        removePendingScript(hash);
        save();
    }

    private synchronized void removePendingScript(String hash) {
        Iterator<PendingScript> it = pendingScripts.iterator();
        while (it.hasNext()) {
            if (it.next().getHash().equals(hash)) {
                it.remove();
                break;
            }
        }
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void clearApprovedScripts() throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        approvedScriptHashes.clear();
        save();
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public Set<PendingSignature> getPendingSignatures() {
        return pendingSignatures;
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] approveSignature(String signature) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, ApprovalContext.create()));
        approvedSignatures.add(signature);
        save();
        return Jenkins.getInstance().getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] aclApproveSignature(String signature) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, ApprovalContext.create()));
        aclApprovedSignatures.add(signature);
        save();
        return Jenkins.getInstance().getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void denySignature(String signature) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, ApprovalContext.create()));
        save();
    }

    // TODO nicer would be to allow the user to actually edit the list directly (with syntax checks)
    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] clearApprovedSignatures() throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        approvedSignatures.clear();
        aclApprovedSignatures.clear();
        save();
        // Should be [[], []] but still returning it for consistency with approve methods.
        return Jenkins.getInstance().getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class)
    public List<ApprovedClasspath> getApprovedClasspaths() {
        return new ArrayList<ApprovedClasspath>(getApprovedClasspathMap().values());
    }

    @Restricted(NoExternalUse.class)
    public List<PendingClasspath> getPendingClasspaths() {
        return new ArrayList<PendingClasspath>(getPendingClasspathMap().values());
    }

    @Restricted(NoExternalUse.class) // for use from Ajax
    @JavaScriptMethod
    public JSON getClasspathRenderInfo() {
        JSONArray pendings = new JSONArray();
        for (PendingClasspath cp : getPendingClasspaths()) {
            pendings.add(new JSONObject().element("hash", cp.getHash()).element("path", ClasspathEntry.urlToPath(cp.getURL())));
        }
        JSONArray approveds = new JSONArray();
        for (ApprovedClasspath cp : getApprovedClasspaths()) {
            approveds.add(new JSONObject().element("hash", cp.getHash()).element("path", ClasspathEntry.urlToPath(cp.getURL())));
        }
        return new JSONArray().element(pendings).element(approveds);
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON approveClasspath(String hash) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        PendingClasspath cp = getPendingClasspath(hash);
        if (cp != null) {
            removePendingClasspath(hash);
            addApprovedClasspath(new ApprovedClasspath(cp.getHash(), cp.getURL()));
            save();
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON denyClasspath(String hash) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        PendingClasspath cp = getPendingClasspath(hash);
        if (cp != null) {
            removePendingClasspath(hash);
            save();
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON denyApprovedClasspath(String hash) throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        if (removeApprovedClasspath(hash)) {
            save();
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public synchronized JSON clearApprovedClasspaths() throws IOException {
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);
        removeAllApprovedClasspath();
        save();
        return getClasspathRenderInfo();
    }

}
