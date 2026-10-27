package com.gerenciador.eventos.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.identityclient.api.MyWallet;

/** API interna da carteira (issue #36): um endpoint por método do {@code MyWalletApi}. Contrato em {@code identity-client/README.md}. */
@RestController
@RequestMapping("/internal/wallets")
public class WalletController {

    private final MyWalletService wallets;

    public WalletController(MyWalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/{userId}")
    public MyWallet getByUserId(@PathVariable Long userId) {
        return wallets.getByUserId(userId);
    }

    /** Idempotente: a carteira já nasce com o usuário (trigger); isto só garante. */
    @PutMapping("/{userId}")
    public MyWallet ensureExists(@PathVariable Long userId) {
        return wallets.ensureExists(userId);
    }

    @GetMapping("/{userId}/exists")
    public boolean walletExists(@PathVariable Long userId) {
        return wallets.walletExists(userId);
    }
}
