use crate::lint;
use codespan_reporting::diagnostic;
use codespan_reporting::files;
use serde::Serialize;
use std::fmt;
use std::ops;

/// File identfiier.
/// References a source file in the source database.
pub type FileId = usize;

/// Source database.
/// Stores the source file contents for reference.
pub type SourceDatabase = files::SimpleFiles<String, String>;

#[derive(Debug, Default, Copy, Clone, Serialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct SourceLocation {
    /// Byte offset into the file (counted from zero).
    pub offset: usize,
    /// Line number (counted from zero).
    pub line: usize,
    /// Column number (counted from zero)
    pub column: usize,
}

#[derive(Default, Copy, Clone, PartialEq, Eq, Serialize)]
pub struct SourceRange {
    pub file: FileId,
    pub start: SourceLocation,
    pub end: SourceLocation,
}

pub trait Annotation: fmt::Debug + Serialize {
    type FieldAnnotation: Default + fmt::Debug + Clone;
    type DeclAnnotation: Default + fmt::Debug;
}

#[derive(Debug, Serialize, Clone)]
#[serde(tag = "kind", rename = "comment")]
pub struct Comment {
    pub loc: SourceRange,
    pub text: String,
}

#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EndiannessValue {
    LittleEndian,
    BigEndian,
}

#[derive(Debug, Copy, Clone, Serialize)]
#[serde(tag = "kind", rename = "endianness_declaration")]
pub struct Endianness {
    pub loc: SourceRange,
    pub value: EndiannessValue,
}

#[derive(Debug, Serialize, Clone)]
#[serde(tag = "kind", rename = "tag")]
pub struct Tag {
    pub id: String,
    pub loc: SourceRange,
    pub value: usize,
}

#[derive(Debug, Serialize, Clone, PartialEq, Eq)]
#[serde(tag = "kind", rename = "constraint")]
pub struct Constraint {
    pub id: String,
    pub loc: SourceRange,
    pub value: Option<usize>,
    pub tag_id: Option<String>,
}

#[derive(Debug, Serialize, Clone, PartialEq, Eq)]
#[serde(tag = "kind")]
pub enum FieldDesc {
    #[serde(rename = "checksum_field")]
    Checksum { field_id: String },
    #[serde(rename = "padding_field")]
    Padding { size: usize },
    #[serde(rename = "size_field")]
    Size { field_id: String, width: usize },
    #[serde(rename = "count_field")]
    Count { field_id: String, width: usize },
    #[serde(rename = "elementsize_field")]
    ElementSize { field_id: String, width: usize },
    #[serde(rename = "body_field")]
    Body,
    #[serde(rename = "payload_field")]
    Payload { size_modifier: Option<String> },
    #[serde(rename = "fixed_field")]
    FixedScalar { width: usize, value: usize },
    #[serde(rename = "fixed_field")]
    FixedEnum { enum_id: String, tag_id: String },
    #[serde(rename = "reserved_field")]
    Reserved { width: usize },
    #[serde(rename = "array_field")]
    Array {
        id: String,
        width: Option<usize>,
        type_id: Option<String>,
        size_modifier: Option<String>,
        size: Option<usize>,
    },
    #[serde(rename = "scalar_field")]
    Scalar { id: String, width: usize },
    #[serde(rename = "typedef_field")]
    Typedef { id: String, type_id: String },
    #[serde(rename = "group_field")]
    Group { group_id: String, constraints: Vec<Constraint> },
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Field<A: Annotation> {
    pub loc: SourceRange,
    #[serde(skip_serializing)]
    pub annot: A::FieldAnnotation,
    #[serde(flatten)]
    pub desc: FieldDesc,
}

#[derive(Debug, Serialize, Clone)]
#[serde(tag = "kind", rename = "test_case")]
pub struct TestCase {
    pub loc: SourceRange,
    pub input: String,
}

#[derive(Debug, Serialize)]
#[serde(tag = "kind")]
pub enum DeclDesc<A: Annotation> {
    #[serde(rename = "checksum_declaration")]
    Checksum { id: String, function: String, width: usize },
    #[serde(rename = "custom_field_declaration")]
    CustomField { id: String, width: Option<usize>, function: String },
    #[serde(rename = "enum_declaration")]
    Enum { id: String, tags: Vec<Tag>, width: usize },
    #[serde(rename = "packet_declaration")]
    Packet {
        id: String,
        constraints: Vec<Constraint>,
        fields: Vec<Field<A>>,
        parent_id: Option<String>,
    },
    #[serde(rename = "struct_declaration")]
    Struct {
        id: String,
        constraints: Vec<Constraint>,
        fields: Vec<Field<A>>,
        parent_id: Option<String>,
    },
    #[serde(rename = "group_declaration")]
    Group { id: String, fields: Vec<Field<A>> },
    #[serde(rename = "test_declaration")]
    Test { type_id: String, test_cases: Vec<TestCase> },
}

#[derive(Debug, Serialize)]
pub struct Decl<A: Annotation> {
    pub loc: SourceRange,
    #[serde(skip_serializing)]
    pub annot: A::DeclAnnotation,
    #[serde(flatten)]
    pub desc: DeclDesc<A>,
}

#[derive(Debug, Serialize)]
pub struct File<A: Annotation> {
    pub version: String,
    pub file: FileId,
    pub comments: Vec<Comment>,
    pub endianness: Endianness,
    pub declarations: Vec<Decl<A>>,
}

impl SourceLocation {
    /// Construct a new source location.
    ///
    /// The `line_starts` indicates the byte offsets where new lines
    /// start in the file. The first element should thus be `0` since
    /// every file has at least one line starting at offset `0`.
    pub fn new(offset: usize, line_starts: &[usize]) -> SourceLocation {
        let mut loc = SourceLocation { offset, line: 0, column: offset };
        for (line, start) in line_starts.iter().enumerate() {
            if *start > offset {
                break;
            }
            loc = SourceLocation { offset, line, column: offset - start };
        }
        loc
    }
}

impl SourceRange {
    pub fn primary(&self) -> diagnostic::Label<FileId> {
        diagnostic::Label::primary(self.file, self.start.offset..self.end.offset)
    }
    pub fn secondary(&self) -> diagnostic::Label<FileId> {
        diagnostic::Label::secondary(self.file, self.start.offset..self.end.offset)
    }
}

impl fmt::Display for SourceRange {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.start.line == self.end.line {
            write!(f, "{}:{}-{}", self.start.line, self.start.column, self.end.column)
        } else {
            write!(
                f,
                "{}:{}-{}:{}",
                self.start.line, self.start.column, self.end.line, self.end.column
            )
        }
    }
}

impl fmt::Debug for SourceRange {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SourceRange").finish_non_exhaustive()
    }
}

impl ops::Add<SourceRange> for SourceRange {
    type Output = SourceRange;

    fn add(self, rhs: SourceRange) -> SourceRange {
        assert!(self.file == rhs.file);
        SourceRange {
            file: self.file,
            start: self.start.min(rhs.start),
            end: self.end.max(rhs.end),
        }
    }
}

impl<A: Annotation> File<A> {
    pub fn new(file: FileId) -> File<A> {
        File {
            version: "1,0".to_owned(),
            comments: vec![],
            // The endianness is mandatory, so this default value will
            // be updated while parsing.
            endianness: Endianness {
                loc: SourceRange::default(),
                value: EndiannessValue::LittleEndian,
            },
            declarations: vec![],
            file,
        }
    }

    /// Iterate over the children of the selected declaration.
    /// /!\ This method is unsafe to use if the file contains cyclic
    /// declarations, use with caution.
    pub fn iter_children<'d>(&'d self, decl: &'d Decl<A>) -> impl Iterator<Item = &'d Decl<A>> {
        self.declarations.iter().filter(|other_decl| other_decl.parent_id() == decl.id())
    }
}

impl<A: Annotation> Decl<A> {
    pub fn new(loc: SourceRange, desc: DeclDesc<A>) -> Decl<A> {
        Decl { loc, annot: Default::default(), desc }
    }

    pub fn annotate<F, B: Annotation>(
        &self,
        annot: B::DeclAnnotation,
        annotate_fields: F,
    ) -> Decl<B>
    where
        F: FnOnce(&[Field<A>]) -> Vec<Field<B>>,
    {
        let desc = match &self.desc {
            DeclDesc::Checksum { id, function, width } => {
                DeclDesc::Checksum { id: id.clone(), function: function.clone(), width: *width }
            }
            DeclDesc::CustomField { id, width, function } => {
                DeclDesc::CustomField { id: id.clone(), width: *width, function: function.clone() }
            }
            DeclDesc::Enum { id, tags, width } => {
                DeclDesc::Enum { id: id.clone(), tags: tags.clone(), width: *width }
            }

            DeclDesc::Test { type_id, test_cases } => {
                DeclDesc::Test { type_id: type_id.clone(), test_cases: test_cases.clone() }
            }
            DeclDesc::Packet { id, constraints, parent_id, fields } => DeclDesc::Packet {
                id: id.clone(),
                constraints: constraints.clone(),
                parent_id: parent_id.clone(),
                fields: annotate_fields(fields),
            },
            DeclDesc::Struct { id, constraints, parent_id, fields } => DeclDesc::Struct {
                id: id.clone(),
                constraints: constraints.clone(),
                parent_id: parent_id.clone(),
                fields: annotate_fields(fields),
            },
            DeclDesc::Group { id, fields } => {
                DeclDesc::Group { id: id.clone(), fields: annotate_fields(fields) }
            }
        };
        Decl { loc: self.loc, desc, annot }
    }

    pub fn id(&self) -> Option<&str> {
        match &self.desc {
            DeclDesc::Test { .. } => None,
            DeclDesc::Checksum { id, .. }
            | DeclDesc::CustomField { id, .. }
            | DeclDesc::Enum { id, .. }
            | DeclDesc::Packet { id, .. }
            | DeclDesc::Struct { id, .. }
            | DeclDesc::Group { id, .. } => Some(id),
        }
    }

    pub fn parent_id(&self) -> Option<&str> {
        match &self.desc {
            DeclDesc::Packet { parent_id, .. } | DeclDesc::Struct { parent_id, .. } => {
                parent_id.as_deref()
            }
            _ => None,
        }
    }

    pub fn constraints(&self) -> std::slice::Iter<'_, Constraint> {
        match &self.desc {
            DeclDesc::Packet { constraints, .. } | DeclDesc::Struct { constraints, .. } => {
                constraints.iter()
            }
            _ => [].iter(),
        }
    }

    /// Determine the size of a declaration type in bits, if possible.
    ///
    /// If the type is dynamically sized (e.g. contains an array or
    /// payload), `None` is returned. If `skip_payload` is set,
    /// payload and body fields are counted as having size `0` rather
    /// than a variable size.
    pub fn width(&self, scope: &lint::Scope<'_>, skip_payload: bool) -> Option<usize> {
        match &self.desc {
            DeclDesc::Enum { width, .. } | DeclDesc::Checksum { width, .. } => Some(*width),
            DeclDesc::CustomField { width, .. } => *width,
            DeclDesc::Packet { fields, parent_id, .. }
            | DeclDesc::Struct { fields, parent_id, .. } => {
                let mut packet_size = match parent_id {
                    None => 0,
                    Some(id) => scope.typedef.get(id.as_str())?.width(scope, true)?,
                };
                for field in fields.iter() {
                    packet_size += field.width(scope, skip_payload)?;
                }
                Some(packet_size)
            }
            DeclDesc::Group { .. } | DeclDesc::Test { .. } => None,
        }
    }

    pub fn fields(&self) -> std::slice::Iter<'_, Field<A>> {
        match &self.desc {
            DeclDesc::Packet { fields, .. }
            | DeclDesc::Struct { fields, .. }
            | DeclDesc::Group { fields, .. } => fields.iter(),
            _ => [].iter(),
        }
    }

    pub fn kind(&self) -> &str {
        match &self.desc {
            DeclDesc::Checksum { .. } => "checksum",
            DeclDesc::CustomField { .. } => "custom field",
            DeclDesc::Enum { .. } => "enum",
            DeclDesc::Packet { .. } => "packet",
            DeclDesc::Struct { .. } => "struct",
            DeclDesc::Group { .. } => "group",
            DeclDesc::Test { .. } => "test",
        }
    }
}

impl<A: Annotation> Field<A> {
    pub fn annotate<B: Annotation>(&self, annot: B::FieldAnnotation) -> Field<B> {
        Field { loc: self.loc, annot, desc: self.desc.clone() }
    }

    pub fn id(&self) -> Option<&str> {
        match &self.desc {
            FieldDesc::Checksum { .. }
            | FieldDesc::Padding { .. }
            | FieldDesc::Size { .. }
            | FieldDesc::Count { .. }
            | FieldDesc::ElementSize { .. }
            | FieldDesc::Body
            | FieldDesc::Payload { .. }
            | FieldDesc::FixedScalar { .. }
            | FieldDesc::FixedEnum { .. }
            | FieldDesc::Reserved { .. }
            | FieldDesc::Group { .. } => None,
            FieldDesc::Array { id, .. }
            | FieldDesc::Scalar { id, .. }
            | FieldDesc::Typedef { id, .. } => Some(id),
        }
    }

    pub fn is_bitfield(&self, scope: &lint::Scope<'_>) -> bool {
        match &self.desc {
            FieldDesc::Size { .. }
            | FieldDesc::Count { .. }
            | FieldDesc::ElementSize { .. }
            | FieldDesc::FixedScalar { .. }
            | FieldDesc::FixedEnum { .. }
            | FieldDesc::Reserved { .. }
            | FieldDesc::Scalar { .. } => true,
            FieldDesc::Typedef { type_id, .. } => {
                let field = scope.typedef.get(type_id.as_str());
                matches!(field, Some(Decl { desc: DeclDesc::Enum { .. }, .. }))
            }
            _ => false,
        }
    }

    pub fn declaration<'a>(
        &self,
        scope: &'a lint::Scope<'a>,
    ) -> Option<&'a crate::parser::ast::Decl> {
        match &self.desc {
            FieldDesc::FixedEnum { enum_id, .. } => scope.typedef.get(enum_id).copied(),
            FieldDesc::Array { type_id: Some(type_id), .. } => scope.typedef.get(type_id).copied(),
            FieldDesc::Typedef { type_id, .. } => scope.typedef.get(type_id.as_str()).copied(),
            _ => None,
        }
    }

    /// Determine the size of a field in bits, if possible.
    ///
    /// If the field is dynamically sized (e.g. unsized array or
    /// payload field), `None` is returned. If `skip_payload` is set,
    /// payload and body fields are counted as having size `0` rather
    /// than a variable size.
    pub fn width(&self, scope: &lint::Scope<'_>, skip_payload: bool) -> Option<usize> {
        match &self.desc {
            FieldDesc::Scalar { width, .. }
            | FieldDesc::Size { width, .. }
            | FieldDesc::Count { width, .. }
            | FieldDesc::ElementSize { width, .. }
            | FieldDesc::Reserved { width, .. }
            | FieldDesc::FixedScalar { width, .. } => Some(*width),
            FieldDesc::FixedEnum { .. } => self.declaration(scope)?.width(scope, false),
            FieldDesc::Padding { .. } => todo!(),
            FieldDesc::Array { size: Some(size), width, .. } => {
                let width = width.or_else(|| self.declaration(scope)?.width(scope, false))?;
                Some(width * size)
            }
            FieldDesc::Typedef { .. } => self.declaration(scope)?.width(scope, false),
            FieldDesc::Checksum { .. } => Some(0),
            FieldDesc::Payload { .. } | FieldDesc::Body { .. } if skip_payload => Some(0),
            _ => None,
        }
    }

    pub fn kind(&self) -> &str {
        match &self.desc {
            FieldDesc::Checksum { .. } => "payload",
            FieldDesc::Padding { .. } => "padding",
            FieldDesc::Size { .. } => "size",
            FieldDesc::Count { .. } => "count",
            FieldDesc::ElementSize { .. } => "elementsize",
            FieldDesc::Body { .. } => "body",
            FieldDesc::Payload { .. } => "payload",
            FieldDesc::FixedScalar { .. } | FieldDesc::FixedEnum { .. } => "fixed",
            FieldDesc::Reserved { .. } => "reserved",
            FieldDesc::Group { .. } => "group",
            FieldDesc::Array { .. } => "array",
            FieldDesc::Scalar { .. } => "scalar",
            FieldDesc::Typedef { .. } => "typedef",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_location_new() {
        let line_starts = &[0, 20, 80, 120, 150];
        assert_eq!(
            SourceLocation::new(0, line_starts),
            SourceLocation { offset: 0, line: 0, column: 0 }
        );
        assert_eq!(
            SourceLocation::new(10, line_starts),
            SourceLocation { offset: 10, line: 0, column: 10 }
        );
        assert_eq!(
            SourceLocation::new(50, line_starts),
            SourceLocation { offset: 50, line: 1, column: 30 }
        );
        assert_eq!(
            SourceLocation::new(100, line_starts),
            SourceLocation { offset: 100, line: 2, column: 20 }
        );
        assert_eq!(
            SourceLocation::new(1000, line_starts),
            SourceLocation { offset: 1000, line: 4, column: 850 }
        );
    }

    #[test]
    fn source_location_new_no_crash_with_empty_line_starts() {
        let loc = SourceLocation::new(100, &[]);
        assert_eq!(loc, SourceLocation { offset: 100, line: 0, column: 100 });
    }
}
