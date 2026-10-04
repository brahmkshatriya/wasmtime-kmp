use std::{
    collections::{BTreeSet, VecDeque},
    convert::Infallible,
    env,
    error::Error as StdError,
    fs,
    path::Path,
};

use wasm_encoder::{
    reencode::{Error, Reencode},
    Module, TypeSection,
};
use wasmparser::{Parser, Payload, RecGroup};

#[derive(Default)]
struct TypeReferenceCollector {
    references: BTreeSet<u32>,
    skip_type_section: bool,
}

impl Reencode for TypeReferenceCollector {
    type Error = Infallible;

    fn type_index(&mut self, ty: u32) -> Result<u32, Error<Self::Error>> {
        self.references.insert(ty);
        Ok(ty)
    }

    fn parse_type_section(
        &mut self,
        types: &mut TypeSection,
        section: wasmparser::TypeSectionReader<'_>,
    ) -> Result<(), Error<Self::Error>> {
        if self.skip_type_section {
            Ok(())
        } else {
            wasm_encoder::reencode::utils::parse_type_section(self, types, section)
        }
    }
}

struct TypePruner {
    type_map: Vec<u32>,
    keep_groups: Vec<bool>,
}

impl Reencode for TypePruner {
    type Error = Infallible;

    fn type_index(&mut self, ty: u32) -> Result<u32, Error<Self::Error>> {
        let mapped = self.type_map[ty as usize];
        assert_ne!(mapped, u32::MAX, "reference to pruned type {ty}");
        Ok(mapped)
    }

    fn parse_type_section(
        &mut self,
        types: &mut TypeSection,
        section: wasmparser::TypeSectionReader<'_>,
    ) -> Result<(), Error<Self::Error>> {
        for (group_index, group) in section.into_iter().enumerate() {
            let group = group?;
            if self.keep_groups[group_index] {
                self.parse_recursive_type_group(types.ty(), group)?;
            }
        }
        Ok(())
    }
}

struct PrunePlan {
    keep_groups: Vec<bool>,
    type_map: Vec<u32>,
    original_types: usize,
    retained_types: usize,
}

fn collect_type_groups(bytes: &[u8]) -> Result<Vec<RecGroup>, Box<dyn StdError>> {
    let mut groups = Vec::new();
    for payload in Parser::new(0).parse_all(bytes) {
        if let Payload::TypeSection(section) = payload? {
            for group in section {
                groups.push(group?);
            }
        }
    }
    Ok(groups)
}

fn build_prune_plan(bytes: &[u8]) -> Result<PrunePlan, Box<dyn StdError>> {
    let groups = collect_type_groups(bytes)?;
    let mut type_to_group = Vec::new();
    for (group_index, group) in groups.iter().enumerate() {
        type_to_group.extend(std::iter::repeat_n(group_index, group.types().len()));
    }

    let original_types = type_to_group.len();
    if original_types == 0 {
        return Ok(PrunePlan {
            keep_groups: vec![true; groups.len()],
            type_map: Vec::new(),
            original_types: 0,
            retained_types: 0,
        });
    }

    // First find every type referenced from imports, functions, globals,
    // element/data initializers, and instructions. The type section itself is
    // intentionally skipped here; its dependencies are followed below only
    // for groups that are reachable from these roots.
    let mut roots = TypeReferenceCollector {
        skip_type_section: true,
        ..Default::default()
    };
    let mut ignored_module = Module::new();
    roots.parse_core_module(&mut ignored_module, Parser::new(0), bytes)?;

    let mut keep_groups = vec![false; groups.len()];
    let mut queue = VecDeque::new();
    for &ty in &roots.references {
        let group_index = *type_to_group
            .get(ty as usize)
            .ok_or_else(|| format!("type index {ty} is outside the type section"))?;
        if !keep_groups[group_index] {
            keep_groups[group_index] = true;
            queue.push_back(group_index);
        }
    }

    // WebAssembly GC types can refer to supertypes and to other types in
    // fields/signatures. Keep the complete transitive dependency closure and
    // preserve recursion groups as indivisible units.
    while let Some(group_index) = queue.pop_front() {
        let mut dependencies = TypeReferenceCollector::default();
        let mut scratch = TypeSection::new();
        dependencies.parse_recursive_type_group(scratch.ty(), groups[group_index].clone())?;
        for ty in dependencies.references {
            let dependency_group = *type_to_group
                .get(ty as usize)
                .ok_or_else(|| format!("type index {ty} is outside the type section"))?;
            if !keep_groups[dependency_group] {
                keep_groups[dependency_group] = true;
                queue.push_back(dependency_group);
            }
        }
    }

    let mut type_map = vec![u32::MAX; original_types];
    let mut old_type_index = 0usize;
    let mut new_type_index = 0u32;
    for (group_index, group) in groups.iter().enumerate() {
        let group_len = group.types().len();
        if keep_groups[group_index] {
            for offset in 0..group_len {
                type_map[old_type_index + offset] = new_type_index;
                new_type_index += 1;
            }
        }
        old_type_index += group_len;
    }

    Ok(PrunePlan {
        keep_groups,
        type_map,
        original_types,
        retained_types: new_type_index as usize,
    })
}

fn prune(input: &Path, output: &Path) -> Result<(), Box<dyn StdError>> {
    let bytes = fs::read(input)?;
    let plan = build_prune_plan(&bytes)?;
    let mut pruner = TypePruner {
        type_map: plan.type_map,
        keep_groups: plan.keep_groups,
    };
    let mut module = Module::new();
    pruner.parse_core_module(&mut module, Parser::new(0), &bytes)?;
    let encoded = module.finish();

    // Never publish an optimization result that the reference parser rejects.
    wasmparser::Validator::new().validate_all(&encoded)?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(output, &encoded)?;

    let saved = bytes.len().saturating_sub(encoded.len());
    let percent = if bytes.is_empty() {
        0.0
    } else {
        100.0 * saved as f64 / bytes.len() as f64
    };
    println!(
        "{}: {} -> {} bytes, types {} -> {} (saved {} bytes, {:.1}%)",
        input.display(),
        bytes.len(),
        encoded.len(),
        plan.original_types,
        plan.retained_types,
        saved,
        percent,
    );
    Ok(())
}


fn print_import_modules(input: &Path) -> Result<(), Box<dyn StdError>> {
    let bytes = fs::read(input)?;
    let mut modules = BTreeSet::new();
    for payload in Parser::new(0).parse_all(&bytes) {
        if let Payload::ImportSection(section) = payload? {
            for import in section.into_imports() {
                modules.insert(import?.module.to_owned());
            }
        }
    }
    for module in modules {
        println!("{module}");
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn StdError>> {
    let mut args = env::args_os();
    let program = args.next().unwrap_or_default();
    let first = args.next().ok_or_else(|| {
        format!(
            "usage: {} <input.wasm> <output.wasm> | imports <input.wasm>",
            Path::new(&program).display()
        )
    })?;
    if first == "imports" {
        let input = args.next().ok_or("imports requires <input.wasm>")?;
        if args.next().is_some() {
            return Err("imports accepts exactly one input".into());
        }
        return print_import_modules(Path::new(&input));
    }
    let output = args.next().ok_or_else(|| {
        format!(
            "usage: {} <input.wasm> <output.wasm> | imports <input.wasm>",
            Path::new(&program).display()
        )
    })?;
    if args.next().is_some() {
        return Err("prune accepts exactly an input and output".into());
    }
    prune(Path::new(&first), Path::new(&output))
}
