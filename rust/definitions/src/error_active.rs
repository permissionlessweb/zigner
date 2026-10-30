//! Errors occurring on the active side, i.e. while operating `generate_message`
//! client
//!
//! Active side deals with both preparation of cold database that would be
//! loaded in Vault on build and with hot database operations. Cold database
//! could be the *release* cold database (the actual one for Vault build) or
//! the *test* cold database (with test Alice identities, used for tests).

use crate::error::MetadataError;

/// Error checking metadata file
#[derive(Debug)]
pub enum Check {
    /// Metadata extracted from the metadata file is not suitable to be used in
    /// Vault.
    ///
    /// Associated data is [`MetadataError`] specifying what exactly is wrong
    /// with the metadata.
    FaultyMetadata(MetadataError),

    /// Unable to read directory with default metadata
    MetadataFile(std::io::Error),
}
