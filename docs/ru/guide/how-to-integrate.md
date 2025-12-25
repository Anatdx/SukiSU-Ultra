# Руководство по интеграции

YukiSU может быть интегрирован как в ядра GKI, так и в ядра, не относящиеся к GKI, и был обратно портирован на версию 4.14.

Некоторые настройки OEM могут привести к тому, что до 50 % кода ядра будет происходить извне дерева ядра, а не из исходного Linux или ACK. Следовательно, индивидуальные функции ядер, не относящихся к GKI, приводят к значительной фрагментации ядра, и у нас нет универсального метода для их сборки. Поэтому мы не можем предоставить загрузочные образы для ядер, не относящихся к GKI.

Предпосылка: ядро с открытым исходным кодом, способное к загрузке.

## Методы подключения

1. **Подключение системного вызова:**

   - Применимо к загружаемым модулям ядра (LKM) или GKI с этим подключением. (Поддерживается в `5.10+`)
   - Требует `CONFIG_KSU_SYSCALL_HOOK=y` & `CONFIG_KPROBES=y`, `CONFIG_KRETPROBES=y`, `CONFIG_HAVE_SYSCALL_TRACEPOINTS=y`

2. **Ручной хук:**

- [Дополнительные сведения см. в этом репозитории](https://github.com/rksuorg/kernel_patches)
- Метод хука по умолчанию для ядер, отличных от GKI; `CONFIG_KPROBES` по умолчанию отключен.
   - Требуется `CONFIG_KSU_MANUAL_HOOK=y`
   - См. [руководство по kernelsu](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/how-to-integrate-for-non-gki.md#manually-modify-the-kernel-source)
   - См. [`guide/how-to-integrate.md`](how-to-integrate.md)
   - Дополнительная ссылка: [backslashxx hooks](https://github.com/backslashxx/KernelSU/issues/5)

### Как добавить драйвер ядра YukiSU в исходный код ядра

- Основная ветвь (обычно используется исключительно для сборок LKM)

```sh
curl -LSs «https://raw.githubusercontent.com/YukiSU/YukiSU/main/kernel/setup.sh» | bash -s main
```

- Встроенная ветвь (для сборок GKI/non-GKI, дополнительная поддержка susfs)
```sh
curl -LSs «https://raw.githubusercontent.com/YukiSU/YukiSU/main/kernel/setup.sh» | bash -s builtin
```
