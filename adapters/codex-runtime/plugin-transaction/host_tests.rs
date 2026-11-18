#[path = "transaction.rs"] mod transaction;
use std::fs;
use std::path::Path;
use std::process::Command;
use transaction::StoreLock;

fn temp() -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!("plugin-tx-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    fs::create_dir_all(&path).unwrap(); path
}
fn install(root: &Path, value: &str) {
    let lock = StoreLock::acquire(root).unwrap();
    lock.install("market", "plugin", |stage, _| { fs::create_dir_all(stage)?; fs::write(stage.join("version"), value)?; fs::write(stage.join("payload"), value.repeat(128)) }).unwrap();
}
fn read(root: &Path) -> String { fs::read_to_string(root.join("cache/market/plugin/version")).unwrap() }
fn backup(root: &Path) -> String { fs::read_to_string(root.join(".backups/market/plugin/version")).unwrap() }
fn worker(root: &Path, mode: &str, crash: Option<&str>) -> Command {
    let mut c = Command::new(std::env::current_exe().unwrap());
    c.arg("--exact").arg("child").arg("--nocapture").env("PLUGIN_TEST_ROOT", root).env("PLUGIN_TEST_MODE", mode);
    if let Some(step) = crash { c.env("PLUGIN_TEST_CRASH", step); } c
}
#[test] fn child() {
    let Ok(root) = std::env::var("PLUGIN_TEST_ROOT") else { return }; let root = Path::new(&root);
    let mode = std::env::var("PLUGIN_TEST_MODE").unwrap();
    if mode == "timeout" {
        let start = std::time::Instant::now();
        match StoreLock::acquire(root) {
            Err(err) if err.kind() == std::io::ErrorKind::TimedOut => (),
            _ => panic!("contended lock must time out"),
        }
        assert!(start.elapsed() >= std::time::Duration::from_secs(9));
        assert!(start.elapsed() < std::time::Duration::from_secs(15));
        return;
    }
    if mode == "prepare_crash" {
        let lock = StoreLock::acquire(root).unwrap();
        let _ = lock.install("market", "plugin", |stage, _| {
            fs::create_dir_all(stage)?;
            fs::write(stage.join("partial"), "incomplete")?;
            std::process::exit(99)
        });
    }
    if mode == "install_enabled" {
        StoreLock::acquire(root).unwrap().install_enabled("market", "plugin", |stage, _| {
            fs::create_dir_all(stage)?; fs::write(stage.join("version"), "v2")?;
            fs::write(stage.join("payload"), "v2".repeat(128))
        }).unwrap();
        return;
    }
    if mode == "uninstall" {
        StoreLock::acquire(root).unwrap().uninstall("market", "plugin").unwrap();
        return;
    }
    if mode == "rollback" {
        let lock = StoreLock::acquire(root).unwrap();
        lock.rollback("market", "plugin", |b, stage| {
            fs::create_dir_all(stage)?;
            fs::copy(b.join("version"), stage.join("version"))?;
            fs::copy(b.join("payload"), stage.join("payload"))?;
            Ok(())
        }).unwrap();
        return;
    }
    let n = if mode == "race" { 50 } else { 1 };
    for i in 0..n { install(root, &format!("{}-{i}", std::process::id())); }
}
#[test] fn upgrade_and_rollback() {
    let root = temp(); install(&root, "v1"); install(&root, "v2");
    assert_eq!(read(&root), "v2"); assert_eq!(backup(&root), "v1");
    let lock = StoreLock::acquire(&root).unwrap();
    lock.rollback("market", "plugin", |b, stage| { fs::create_dir_all(stage)?; fs::copy(b.join("version"), stage.join("version"))?; fs::copy(b.join("payload"), stage.join("payload"))?; Ok(()) }).unwrap();
    drop(lock); assert_eq!(read(&root), "v1"); assert_eq!(backup(&root), "v2"); fs::remove_dir_all(root).unwrap();
}
#[test] fn rollback_pins_and_rejects_later_install() {
    let root = temp(); install(&root, "v1"); install(&root, "v2");
    let lock = StoreLock::acquire(&root).unwrap();
    lock.rollback("market", "plugin", |b, stage| {
        fs::create_dir_all(stage)?;
        fs::copy(b.join("version"), stage.join("version"))?;
        fs::copy(b.join("payload"), stage.join("payload"))?;
        Ok(())
    }).unwrap();
    assert!(lock.is_pinned("market", "plugin").unwrap());
    let err = lock.install("market", "plugin", |_, _| panic!("pinned install must not prepare")).unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::PermissionDenied);
    drop(lock);
    assert_eq!(read(&root), "v1"); assert_eq!(backup(&root), "v2");
    fs::remove_dir_all(root).unwrap();
}
#[test] fn interrupted_rollback_commits_version_and_pin_together() {
    for step in ["prepared", "backup_moved", "backup_saved", "target_moved", "target_saved", "activated", "committed"] {
        let root = temp(); install(&root, "v1"); install(&root, "v2");
        assert_eq!(worker(&root, "rollback", Some(step)).status().unwrap().code(), Some(99));
        let lock = StoreLock::acquire(&root).unwrap();
        if step == "committed" {
            assert_eq!(read(&root), "v1"); assert!(lock.is_pinned("market", "plugin").unwrap());
        } else {
            assert_eq!(read(&root), "v2"); assert!(!lock.is_pinned("market", "plugin").unwrap());
        }
        drop(lock); fs::remove_dir_all(root).unwrap();
    }
}
#[test] fn recover_crash_at_each_step() {
    for step in ["prepared", "backup_moved", "backup_saved", "target_moved", "target_saved", "activated", "committed"] {
        let root = temp(); install(&root, "v0"); install(&root, "v1");
        assert_eq!(worker(&root, "crash", Some(step)).status().unwrap().code(), Some(99));
        drop(StoreLock::acquire(&root).unwrap());
        if step == "committed" { assert_ne!(read(&root), "v1"); assert_eq!(backup(&root), "v1"); }
        else { assert_eq!(read(&root), "v1", "{step}"); assert_eq!(backup(&root), "v0", "{step}"); }
        assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
    }
}
#[test] fn two_processes_fifty_rounds_each() {
    let root = temp(); install(&root, "initial");
    let mut a = worker(&root, "race", None).spawn().unwrap(); let mut b = worker(&root, "race", None).spawn().unwrap();
    assert!(a.wait().unwrap().success()); assert!(b.wait().unwrap().success());
    drop(StoreLock::acquire(&root).unwrap());
    for dir in [root.join("cache/market/plugin"), root.join(".backups/market/plugin")] {
        let v = fs::read_to_string(dir.join("version")).unwrap();
        assert!(v.ends_with(|c: char| c.is_ascii_digit())); assert!(v.contains('-'));
        assert_eq!(fs::read_to_string(dir.join("payload")).unwrap(), v.repeat(128));
    }
    assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
}

#[test] fn recover_first_install_and_upgrade_without_prior_backup() {
    for initial in [false, true] {
        for step in ["prepared", "backup_moved", "backup_saved", "target_moved", "target_saved", "activated", "committed"] {
            let root = temp(); if initial { install(&root, "v1"); }
            assert_eq!(worker(&root, "crash", Some(step)).status().unwrap().code(), Some(99));
            drop(StoreLock::acquire(&root).unwrap());
            let target = root.join("cache/market/plugin/version");
            if step == "committed" { assert!(target.exists()); if initial { assert_eq!(backup(&root), "v1"); } }
            else if initial { assert_eq!(read(&root), "v1"); assert!(!root.join(".backups/market/plugin").exists()); }
            else { assert!(!target.exists()); }
            fs::remove_dir_all(root).unwrap();
        }
    }
}

#[test] fn recovery_is_itself_restartable() {
    for step in ["recovery_stage", "recovery_target", "recovery_backup"] {
        let root = temp(); install(&root, "v0"); install(&root, "v1");
        assert_eq!(worker(&root, "crash", Some("activated")).status().unwrap().code(), Some(99));
        assert_eq!(worker(&root, "crash", Some(step)).status().unwrap().code(), Some(99));
        drop(StoreLock::acquire(&root).unwrap());
        assert_eq!(read(&root), "v1"); assert_eq!(backup(&root), "v0");
        fs::remove_dir_all(root).unwrap();
    }
}

#[test] fn recovery_discards_interrupted_staging() {
    let root = temp(); install(&root, "v0"); install(&root, "v1");
    assert_eq!(worker(&root, "prepare_crash", None).status().unwrap().code(), Some(99));
    drop(StoreLock::acquire(&root).unwrap());
    assert_eq!(read(&root), "v1"); assert_eq!(backup(&root), "v0");
    assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
}

#[test] fn uninstall_recovers_before_and_after_journal() {
    for step in ["uninstall_before_journal", "uninstall_after_journal", "uninstall_config", "uninstall_target", "uninstall_backup"] {
        let root = temp(); install(&root, "v1"); install(&root, "v2");
        fs::write(root.join("test-enabled"), "true").unwrap();
        assert_eq!(worker(&root, "uninstall", Some(step)).status().unwrap().code(), Some(99));
        drop(StoreLock::acquire(&root).unwrap());
        if step == "uninstall_before_journal" {
            assert_eq!(read(&root), "v2"); assert_eq!(backup(&root), "v1");
            assert!(root.join("test-enabled").exists());
        } else {
            assert!(!root.join("cache/market/plugin").exists(), "{step}");
            assert!(!root.join(".backups/market/plugin").exists(), "{step}");
            assert!(!root.join("test-enabled").exists(), "{step}");
        }
        assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
    }
}

#[test] fn install_enabled_recovers_cache_and_config_together() {
    for step in ["prepared", "backup_moved", "backup_saved", "target_moved", "target_saved", "activated", "committed", "install_config"] {
        let root = temp(); install(&root, "v1");
        assert_eq!(worker(&root, "install_enabled", Some(step)).status().unwrap().code(), Some(99));
        drop(StoreLock::acquire(&root).unwrap());
        if step == "committed" || step == "install_config" {
            assert_eq!(read(&root), "v2"); assert!(root.join("test-enabled").exists());
        } else {
            assert_eq!(read(&root), "v1"); assert!(!root.join("test-enabled").exists());
        }
        assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
    }
}

#[test] fn install_enabled_recovery_can_itself_be_interrupted() {
    for step in ["recovery_stage", "recovery_target", "recovery_backup"] {
        let root = temp(); install(&root, "v0"); install(&root, "v1");
        assert_eq!(worker(&root, "install_enabled", Some("activated")).status().unwrap().code(), Some(99));
        assert_eq!(worker(&root, "install_enabled", Some(step)).status().unwrap().code(), Some(99));
        drop(StoreLock::acquire(&root).unwrap());
        assert_eq!(read(&root), "v1"); assert_eq!(backup(&root), "v0");
        assert!(!root.join("test-enabled").exists());
        assert!(!root.join(".transaction").exists()); fs::remove_dir_all(root).unwrap();
    }
}

#[test] fn lock_timeout_is_bounded_across_processes() {
    let root = temp();
    let lock = StoreLock::acquire(&root).unwrap();
    assert!(worker(&root, "timeout", None).status().unwrap().success());
    drop(lock);
    drop(StoreLock::acquire(&root).unwrap());
    fs::remove_dir_all(root).unwrap();
}
