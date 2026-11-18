//! Cross-process plugin cache transaction. All paths are below CODEX_HOME/plugins.
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub struct StoreLock { root: PathBuf, _file: File }

fn valid(s: &str) -> bool {
    !s.is_empty() && s != "." && s != ".." && s.bytes().all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
}
fn remove(p: &Path) -> io::Result<()> {
    match fs::symlink_metadata(p) {
        Ok(m) if m.is_dir() => fs::remove_dir_all(p),
        Ok(_) => fs::remove_file(p),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}
fn sync_dir(p: &Path) -> io::Result<()> { File::open(p)?.sync_all() }
fn rename(a: &Path, b: &Path) -> io::Result<()> {
    if let Some(p) = b.parent() { fs::create_dir_all(p)?; }
    fs::rename(a, b)?;
    sync_dir(a.parent().unwrap())?;
    if a.parent() != b.parent() { sync_dir(b.parent().unwrap())?; }
    Ok(())
}

impl StoreLock {
    pub fn acquire(root: &Path) -> io::Result<Self> {
        fs::create_dir_all(root)?;
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(root.join(".lock"))?;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        loop {
            match file.try_lock() {
                Ok(()) => break,
                Err(std::fs::TryLockError::WouldBlock) if std::time::Instant::now() < deadline =>
                    std::thread::sleep(std::time::Duration::from_millis(20)),
                Err(std::fs::TryLockError::WouldBlock) => return Err(io::Error::new(io::ErrorKind::TimedOut, "timed out waiting for config/plugin lock")),
                Err(std::fs::TryLockError::Error(err)) => return Err(err),
            }
        }
        let lock = Self { root: root.to_path_buf(), _file: file };
        lock.recover()?;
        Ok(lock)
    }
    fn tx(&self) -> PathBuf { self.root.join(".transaction") }
    fn target(&self, market: &str, name: &str) -> PathBuf { self.root.join("cache").join(market).join(name) }
    fn backup(&self, market: &str, name: &str) -> PathBuf { self.root.join(".backups").join(market).join(name) }
    pub fn is_pinned(&self, market: &str, name: &str) -> io::Result<bool> {
        Self::check(market, name)?;
        match fs::symlink_metadata(self.target(market, name).join(".taku-rollback-pin")) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e),
        }
    }
    fn check(market: &str, name: &str) -> io::Result<()> {
        if valid(market) && valid(name) { Ok(()) } else { Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid plugin identity")) }
    }
    fn journal(&self, market: &str, name: &str, had_target: bool, had_backup: bool, step: &str) -> io::Result<()> {
        let tx = self.tx();
        let tmp = tx.join("journal.tmp");
        let mut f = File::create(&tmp)?;
        write!(f, "{{\"market\":\"{market}\",\"name\":\"{name}\",\"had_target\":{had_target},\"had_backup\":{had_backup},\"step\":\"{step}\"}}\n")?;
        f.sync_all()?;
        rename(&tmp, &tx.join("journal.json"))
    }
    fn recover(&self) -> io::Result<()> {
        let tx = self.tx();
        let text = match fs::read_to_string(tx.join("journal.json")) {
            Ok(s) => s,
            Err(e) if e.kind() == io::ErrorKind::NotFound => { remove(&tx)?; return Ok(()); },
            Err(e) => return Err(e),
        };
        let bad = || io::Error::new(io::ErrorKind::InvalidData, "invalid plugin journal");
        if let Some(rest) = text.trim().strip_prefix("{\"uninstall\":\"") {
            let (market, name) = rest.strip_suffix("\"}").ok_or_else(bad)?.split_once('@').ok_or_else(bad)?;
            Self::check(market, name)?;
            self.finish_uninstall(market, name)?;
            return Ok(());
        }
        let rest = text.trim().strip_prefix("{\"market\":\"").ok_or_else(bad)?;
        let (market, rest) = rest.split_once("\",\"name\":\"").ok_or_else(bad)?;
        let (name, rest) = rest.split_once("\",\"had_target\":").ok_or_else(bad)?;
        let (had_target, rest) = rest.split_once(",\"had_backup\":").ok_or_else(bad)?;
        let (had_backup, step) = rest.split_once(",\"step\":\"").ok_or_else(bad)?;
        let step = step.strip_suffix("\"}").ok_or_else(bad)?;
        Self::check(market, name)?;
        let had_target = had_target.parse::<bool>().map_err(|_| bad())?;
        let had_backup = had_backup.parse::<bool>().map_err(|_| bad())?;
        if !["prepared", "backup_saved", "target_saved", "committed", "committed_enabled"].contains(&step) { return Err(bad()); }
        let target = self.target(market, name);
        let backup = self.backup(market, name);
        let previous = tx.join("previous-backup");
        let stage = tx.join("staged");
        if step != "committed" && step != "committed_enabled" {
            // If the staged tree has moved, the target is the new tree. Otherwise a
            // missing target indicates that the old tree has already moved to backup.
            // Move the new tree back to staging, so recovery itself can be interrupted
            // and retried without mistaking the restored target for the new tree.
            if !stage.exists() { rename(&target, &stage)?; }
            crash("recovery_stage");
            if had_target && !target.exists() { rename(&backup, &target)?; }
            crash("recovery_target");
            if previous.exists() { remove(&backup)?; rename(&previous, &backup)?; }
            crash("recovery_backup");
            if !had_backup && !had_target { remove(&backup)?; }
        }
        if step == "committed_enabled" { set_install_config(&self.root, market, name)?; }
        remove(&tx)?;
        sync_dir(&self.root)
    }
    fn finish_uninstall(&self, market: &str, name: &str) -> io::Result<()> {
        clear_uninstall_config(&self.root, market, name)?;
        crash("uninstall_config");
        let target = self.target(market, name);
        remove(&target)?;
        if let Some(parent) = target.parent().filter(|p| p.exists()) { sync_dir(parent)?; }
        crash("uninstall_target");
        let backup = self.backup(market, name);
        remove(&backup)?;
        if let Some(parent) = backup.parent().filter(|p| p.exists()) { sync_dir(parent)?; }
        crash("uninstall_backup");
        remove(&self.tx())?;
        sync_dir(&self.root)
    }
    pub fn uninstall(&self, market: &str, name: &str) -> io::Result<()> {
        Self::check(market, name)?;
        let tx = self.tx();
        fs::create_dir_all(&tx)?;
        sync_dir(&self.root)?;
        crash("uninstall_before_journal");
        let mut file = File::create(tx.join("journal.tmp"))?;
        writeln!(file, "{{\"uninstall\":\"{market}@{name}\"}}")?;
        file.sync_all()?;
        rename(&tx.join("journal.tmp"), &tx.join("journal.json"))?;
        crash("uninstall_after_journal");
        self.finish_uninstall(market, name)
    }
    pub fn install<F>(&self, market: &str, name: &str, prepare: F) -> io::Result<()>
    where F: FnOnce(&Path, &Path) -> io::Result<()> {
        if self.is_pinned(market, name)? {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "plugin is pinned after rollback; uninstall it before reinstalling"));
        }
        self.install_unchecked(market, name, false, prepare)
    }
    pub fn install_enabled<F>(&self, market: &str, name: &str, prepare: F) -> io::Result<()>
    where F: FnOnce(&Path, &Path) -> io::Result<()> {
        if self.is_pinned(market, name)? {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "plugin is pinned after rollback; uninstall it before reinstalling"));
        }
        self.install_unchecked(market, name, true, prepare)
    }
    fn install_unchecked<F>(&self, market: &str, name: &str, enable: bool, prepare: F) -> io::Result<()>
    where F: FnOnce(&Path, &Path) -> io::Result<()> {
        Self::check(market, name)?;
        let tx = self.tx();
        fs::create_dir_all(&tx)?;
        let stage = tx.join("staged");
        let target = self.target(market, name);
        let backup = self.backup(market, name);
        if let Err(e) = prepare(&stage, &backup) { remove(&tx)?; return Err(e); }
        if !stage.is_dir() { remove(&tx)?; return Err(io::Error::new(io::ErrorKind::InvalidData, "missing staged plugin")); }
        let had_target = target.exists();
        let had_backup = backup.exists();
        self.journal(market, name, had_target, had_backup, "prepared")?;
        crash("prepared");
        if had_backup { rename(&backup, &tx.join("previous-backup"))?; }
        crash("backup_moved");
        self.journal(market, name, had_target, had_backup, "backup_saved")?;
        crash("backup_saved");
        if had_target { rename(&target, &backup)?; }
        crash("target_moved");
        self.journal(market, name, had_target, had_backup, "target_saved")?;
        crash("target_saved");
        rename(&stage, &target)?;
        crash("activated");
        self.journal(market, name, had_target, had_backup, if enable { "committed_enabled" } else { "committed" })?;
        crash("committed");
        if enable { set_install_config(&self.root, market, name)?; }
        crash("install_config");
        remove(&tx)?;
        sync_dir(&self.root)
    }
    pub fn rollback<F>(&self, market: &str, name: &str, copy: F) -> io::Result<()>
    where F: FnOnce(&Path, &Path) -> io::Result<()> {
        Self::check(market, name)?;
        if !self.backup(market, name).is_dir() {
            return Err(io::Error::new(io::ErrorKind::NotFound, "no plugin backup available"));
        }
        self.install_unchecked(market, name, false, |stage, backup| {
            copy(backup, stage)?;
            let mut pin = File::create(stage.join(".taku-rollback-pin"))?;
            pin.write_all(b"rollback\n")?;
            pin.sync_all()?;
            Ok(())
        })
    }

}
#[cfg(test)]
fn crash(step: &str) { if std::env::var("PLUGIN_TEST_CRASH").ok().as_deref() == Some(step) { std::process::exit(99); } }
#[cfg(not(test))]
fn crash(_: &str) {}

#[cfg(not(test))]
fn clear_uninstall_config(root: &Path, market: &str, name: &str) -> io::Result<()> {
    crate::plugin_edit::apply_user_plugin_config_edits_unlocked(
        root.parent().ok_or_else(|| io::Error::other("missing Codex home"))?,
        vec![crate::PluginConfigEdit::Clear { plugin_key: format!("{name}@{market}") }],
    )
}
#[cfg(test)]
fn clear_uninstall_config(root: &Path, _market: &str, _name: &str) -> io::Result<()> {
    remove(&root.join("test-enabled"))
}

#[cfg(not(test))]
fn set_install_config(root: &Path, market: &str, name: &str) -> io::Result<()> {
    crate::plugin_edit::apply_user_plugin_config_edits_unlocked(
        root.parent().ok_or_else(|| io::Error::other("missing Codex home"))?,
        vec![crate::PluginConfigEdit::SetEnabled { plugin_key: format!("{name}@{market}"), enabled: true }],
    )
}
#[cfg(test)]
fn set_install_config(root: &Path, _market: &str, _name: &str) -> io::Result<()> {
    fs::write(root.join("test-enabled"), "true")
}
