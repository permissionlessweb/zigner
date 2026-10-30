//! The address Zigner shows must belong to the viewing key it exports
//! (zigner#35), for 12- and 24-word seeds and more than one account.

const SEEDS: [&str; 2] = [
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
];

#[cfg(feature = "penumbra")]
#[test]
fn penumbra_shown_address_is_owned_by_the_exported_fvk() {
    use penumbra_keys::keys::{AddressIndex, FullViewingKey};
    use penumbra_keys::Address;
    use transaction_signing::penumbra::{FullViewingKey as ExportFvk, SpendKeyBytes};

    for seed in SEEDS {
        for account in [0u32, 1] {
            let shown: Address = db_handling::penumbra::receive_address(seed, account)
                .unwrap()
                .parse()
                .unwrap();

            let export =
                ExportFvk::derive_from(&SpendKeyBytes::from_seed_phrase(seed, account).unwrap())
                    .unwrap()
                    .to_bech32m()
                    .unwrap();
            let fvk: FullViewingKey = export.parse().unwrap();

            assert_eq!(
                fvk.address_index(&shown),
                Some(AddressIndex::new(0)),
                "account {account}"
            );
        }
    }
}

#[cfg(feature = "zcash")]
#[test]
fn zcash_shown_address_is_the_exported_ufvk_default() {
    use zcash_address::unified::{Address, Container, Encoding, Fvk, Receiver, Ufvk};

    let seed = SEEDS[1];
    for account in [0u32, 1] {
        let ufvk = transaction_signing::zcash::OrchardSpendingKey::get_ufvk_with_transparent(
            seed, account, true,
        )
        .unwrap();
        let (_, ufvk) = Ufvk::decode(&ufvk).unwrap();
        let fvk = ufvk
            .items()
            .into_iter()
            .find_map(|f| match f {
                Fvk::Orchard(b) => orchard::keys::FullViewingKey::from_bytes(&b),
                _ => None,
            })
            .unwrap();
        let expected = fvk
            .address_at(0u32, orchard::keys::Scope::External)
            .to_raw_address_bytes();

        let (_, shown) =
            db_handling::zcash::derive_zcash_keys(seed, &format!("m/32'/133'/{account}'"), true)
                .unwrap();
        let (_, shown) = Address::decode(&shown).unwrap();
        let shown = shown
            .items()
            .into_iter()
            .find_map(|r| match r {
                Receiver::Orchard(b) => Some(b),
                _ => None,
            })
            .unwrap();
        assert_eq!(shown, expected, "account {account}");
    }
}
