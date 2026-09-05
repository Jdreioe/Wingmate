use std::{env, fs, path::Path, path::PathBuf, process::Command};

fn main() {
    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("manifest directory"));
    let repository = manifest.join("../..");
    check_release_version(&repository);
    let target = env::var("CARGO_CFG_TARGET_OS").expect("target OS");
    let architecture = env::var("CARGO_CFG_TARGET_ARCH").expect("target architecture");
    let profile = env::var("PROFILE").expect("Cargo build profile");
    // MinGW cannot link Kotlin/Native's much larger debug archive into the iced
    // test binary: its IMAGE_REL_AMD64_REL32 relocations overflow. Windows tests
    // therefore use the release bridge even though Cargo itself uses debug.
    let (build_type, binary_folder) = if target == "windows" || profile == "release" {
        ("Release", "releaseStatic")
    } else {
        ("Debug", "debugStatic")
    };
    let (kotlin_target, target_folder) = match (target.as_str(), architecture.as_str()) {
        ("linux", "x86_64") => ("LinuxX64", "linuxX64"),
        ("windows", "x86_64") => ("MingwX64", "mingwX64"),
        ("macos", "x86_64") => ("MacosX64", "macosX64"),
        ("macos", "aarch64") => ("MacosArm64", "macosArm64"),
        pair => panic!("unsupported Wingmate desktop target: {pair:?}"),
    };
    let task = format!("link{build_type}Static{kotlin_target}");
    let library_directory = repository.join(format!(
        "desktopApp/bindings/build/bin/{target_folder}/{binary_folder}"
    ));
    if env::var_os("WINGMATE_KOTLIN_BRIDGE_PREBUILT").is_none() {
        let gradle = if target == "windows" {
            "gradlew.bat"
        } else {
            "gradlew"
        };
        let status = Command::new(repository.join(gradle))
            .current_dir(&repository)
            .arg(format!(":desktopApp:bindings:{task}"))
            .arg("--console=plain")
            .arg("--build-cache")
            .status()
            .expect("could not start Gradle");
        assert!(status.success(), "Kotlin/Native bridge build failed");
    } else {
        assert!(
            library_directory.is_dir(),
            "prebuilt Kotlin/Native bridge is missing from {}",
            library_directory.display()
        );
    }

    println!("cargo:rerun-if-changed=../bindings/src");
    println!("cargo:rerun-if-changed=../bindings/build.gradle.kts");
    println!("cargo:rerun-if-env-changed=PROFILE");
    println!("cargo:rerun-if-env-changed=WINGMATE_KOTLIN_BRIDGE_PREBUILT");
    println!(
        "cargo:rustc-link-search=native={}",
        library_directory.display()
    );
    println!("cargo:rustc-link-lib=static=wingmate_core");
    if target == "linux" {
        println!("cargo:rustc-link-lib=stdc++");
        println!("cargo:rustc-link-lib=z");
        println!("cargo:rustc-link-lib=dl");
        println!("cargo:rustc-link-lib=pthread");
        println!("cargo:rustc-link-lib=m");
    } else if target == "macos" {
        println!("cargo:rustc-link-lib=c++");
        println!("cargo:rustc-link-lib=z");
    } else if target == "windows" {
        println!("cargo:rustc-link-lib=stdc++");
        println!("cargo:rustc-link-lib=z");
        // ktor-io's Windows charset implementation uses Kotlin/Native's
        // platform.iconv klib. Rust performs the final link, so it must carry
        // that klib's static linker option across the FFI boundary.
        println!("cargo:rustc-link-lib=static=iconv");
    }
}

/// ADR-0014 keeps one semantic version for every client, in
/// `version.properties`. Fail the build rather than ship a desktop binary
/// that reports a version no release tag will ever match.
fn check_release_version(repository: &Path) {
    let path = repository.join("version.properties");
    println!("cargo:rerun-if-changed={}", path.display());
    let properties = fs::read_to_string(&path).expect("could not read version.properties");
    let released = properties
        .lines()
        .find_map(|line| line.trim().strip_prefix("versionName="))
        .expect("version.properties declares no versionName")
        .trim()
        .to_string();
    // versionName may be "0.7"; Cargo always spells the patch component out.
    let expected = match released.matches('.').count() {
        0 => format!("{released}.0.0"),
        1 => format!("{released}.0"),
        _ => released.clone(),
    };
    let crate_version = env::var("CARGO_PKG_VERSION").expect("crate version");
    assert_eq!(
        crate_version, expected,
        "desktopApp/rust/Cargo.toml says {crate_version}, but version.properties \
         releases {released}. Update Cargo.toml to {expected} (ADR-0014)."
    );
}
