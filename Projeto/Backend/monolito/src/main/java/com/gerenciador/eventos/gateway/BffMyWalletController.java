package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.identity.api.MyWallet;
import com.gerenciador.eventos.identity.api.MyWalletApi;

/**
 * Camada BFF única para a carteira do usuário (my-wallet): validate, getByUser, ensure.
 * Antes desta issue (#78) vivia em {@code bff.BffApplication.MyWalletBffController}.
 * Comportamento inalterado. Sem chamador no frontend hoje (carteira é criada
 * automaticamente por trigger no cadastro), mas faz parte da superfície BFF.
 */
@RestController
@RequestMapping("/bff/my-wallets")
public class BffMyWalletController {

    private final MyWalletApi myWalletService;

    public BffMyWalletController(MyWalletApi myWalletService) {
        this.myWalletService = myWalletService;
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateWallet(@RequestBody MyWallet wallet) {
        List<String> errors = validateMyWalletBasic(wallet);
        if (errors.isEmpty()) {
            return ResponseEntity.ok("Carteira válida");
        }
        return ResponseEntity.badRequest().body(String.join("; ", errors));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getByUser(@PathVariable Long userId) {
        if (userId == null || userId <= 0) {
            return ResponseEntity.badRequest().body("userId inválido");
        }
        MyWallet wallet = myWalletService.getByUserId(userId);
        return ResponseEntity.ok(wallet);
    }

    @PostMapping("/ensure/{userId}")
    public ResponseEntity<MyWallet> ensureExists(@PathVariable Long userId) {
        return ResponseEntity.ok(myWalletService.ensureExists(userId));
    }

    private List<String> validateMyWalletBasic(MyWallet w) {
        List<String> errors = new ArrayList<>();
        if (w.getUserId() == null || w.getUserId() <= 0) {
            errors.add("user_id é obrigatório e deve ser positivo");
        }
        return errors;
    }
}
